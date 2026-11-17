param([ValidateSet('All','Start','Business','Observe','Faults','Reconcile')][string]$Stage='All', [string]$RunDirectory)
. (Join-Path $PSScriptRoot '../../deploy/scripts/common.ps1')
function Check([bool]$ok,[string]$message) { if(-not $ok){throw $message} }
function Save([string]$name,$value) { $value | ConvertTo-Json -Depth 80 | Set-Content -LiteralPath (Join-Path $evidence $name) -Encoding utf8 }
function Compose([string[]]$arguments) { (Invoke-Program docker ($compose+$arguments)).Output }
function Sql([string]$text) { Invoke-DatabaseSql $compose $text }
function Wait-For([string]$description,[scriptblock]$condition,[int]$seconds=120) {
    $deadline=[DateTime]::UtcNow.AddSeconds($seconds)
    do {if(& $condition){return};Start-Sleep -Seconds 2}while([DateTime]::UtcNow -lt $deadline)
    throw "Timed out: $description"
}
function Api([string]$base,[string]$path,[string]$token='', $body=$null,[string]$method='POST',[string]$traceId='') {
    $headers=@{}
    if($token){$headers.Authorization='Bearer '+$token}
    if($traceId){$headers.traceparent='00-'+$traceId+'-1234567890abcdef-01'}
    $parameters=@{Uri=$base+$path;Method=$method;Headers=$headers;TimeoutSec=25;SkipHttpErrorCheck=$true;ContentType='application/json'}
    if($null -ne $body){$parameters.Body=$body | ConvertTo-Json -Depth 20 -Compress;$parameters.ContentType='application/json'}
    $r=Invoke-WebRequest @parameters
    # PowerShell may expose application/problem+json as bytes rather than text.
    $content=if($r.Content -is [byte[]]){[Text.Encoding]::UTF8.GetString($r.Content)}else{[string]$r.Content}
    $value=if($content){$content | ConvertFrom-Json}else{$null}
    if(-not $path.StartsWith('/auth/')){
        @{path=$path;method=$method;status=[int]$r.StatusCode;body=$value;at=[DateTimeOffset]::Now.ToString('o')} |
            ConvertTo-Json -Depth 25 -Compress | Add-Content -LiteralPath (Join-Path $evidence 'http-business.jsonl') -Encoding utf8
    }
    return @{status=[int]$r.StatusCode;body=$value;requestId=($r.Headers['X-Request-ID'] -join '')}
}
function Post([string]$base,[string]$path,[string]$token='', $body=$null,[string]$traceId='') {
    $r=Api $base $path $token $body 'POST' $traceId
    Check ($r.status -ge 200 -and $r.status -lt 300) "HTTP $path failed with $($r.status): $($r.body | ConvertTo-Json -Compress -Depth 10)"
    return $r.body
}
function Broker([string]$method,[string]$path,$body=$null) {
    $auth=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($settings.RABBITMQ_USERNAME+':'+$settings.RABBITMQ_PASSWORD))
    $p=@{Uri=$broker+$path;Method=$method;Headers=@{Authorization='Basic '+$auth};TimeoutSec=15}
    if($null -ne $body){$p.Body=$body | ConvertTo-Json -Depth 20 -Compress;$p.ContentType='application/json'}
    Invoke-RestMethod @p
}
function Query([string]$expression) {
    $r=Invoke-RestMethod ($prom+'/api/v1/query?query='+[Uri]::EscapeDataString($expression)) -TimeoutSec 15
    Check ($r.status -eq 'success') 'Prometheus query failed'
    return @($r.data.result)
}
function Number([string]$expression) { $r=@(Query $expression);if($r.Count -eq 0){return 0.0};return [double]$r[0].value[1] }
function Ready([string]$base) {try{return (Invoke-RestMethod ($base+'/actuator/health') -TimeoutSec 5).status -eq 'UP'}catch{return $false} }
function Grafana([string]$path) {
    $auth=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes('admin:'+$settings.GRAFANA_PASSWORD))
    Invoke-RestMethod ($grafana+$path) -Headers @{Authorization='Basic '+$auth} -TimeoutSec 20
}
function Read-Trace([string]$id,[string[]]$required) {
    $script:traceResult=$null
    Wait-For "Tempo trace $id has required spans" {
        try {
            $value=Invoke-RestMethod ($tempo+'/api/traces/'+$id) -TimeoutSec 10
            $names=@($value.batches | ForEach-Object {$_.scopeSpans} | ForEach-Object {$_.spans} | ForEach-Object {$_.name})
            if(@($required | Where-Object {$_ -notin $names}).Count -eq 0){$script:traceResult=$value;return $true}
        }catch{}
        return $false
    } 120
    Save "trace-$id.json" $script:traceResult
    return @($script:traceResult.batches | ForEach-Object {$_.scopeSpans} | ForEach-Object {$_.spans} | ForEach-Object {
        @{name=$_.name;spanId=$_.spanId;parentSpanId=$_.parentSpanId;durationMs=([decimal]$_.endTimeUnixNano-[decimal]$_.startTimeUnixNano)/1000000;attributes=$_.attributes;links=$_.links;status=$_.status}
    })
}
function Reconcile {
    $files=@(Get-ChildItem (Join-Path $RepoRoot 'tests/sql') -Filter '*.sql' | Sort-Object Name)
    $sql="SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ; START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY;\n".Replace('\n',[Environment]::NewLine)
    foreach($file in $files){$sql+="SELECT 'BEGIN:$($file.Name)';"+[IO.File]::ReadAllText($file.FullName)+[Environment]::NewLine+"SELECT 'END:$($file.Name)';"+[Environment]::NewLine}
    $text=Sql ($sql+'COMMIT;')
    $text | Set-Content -LiteralPath (Join-Path $evidence 'reconciliation.txt') -Encoding utf8
    $report=@()
    foreach($file in $files){
        $match=[regex]::Match($text,'(?s)BEGIN:'+ [regex]::Escape($file.Name)+'\r?\n(.*?)\r?\n?END:'+ [regex]::Escape($file.Name))
        Check $match.Success "Missing SQL output $($file.Name)"
        $rows=$match.Groups[1].Value.Trim();$diagnostic=$file.Name -eq 'messaging_reconciliation.sql'
        $report+=@{script=$file.Name;diagnostic=$diagnostic;differences=if($diagnostic){$null}elseif($rows){@($rows -split '\r?\n').Count}else{0}}
        if(-not $diagnostic){Check (-not $rows) "Database difference in $($file.Name): $rows"}
    }
    Save 'reconciliation.json' $report
}
if($RunDirectory){
    $evidence=[IO.Path]::GetFullPath($RunDirectory)
    $context=Get-Content (Join-Path $evidence 'context.json') -Raw | ConvertFrom-Json
    $project=$context.project;$envFile=$context.envFile
}else{
    Check ($Stage -in @('All','Start')) 'Specify -RunDirectory for later stages'
    $runId=(Get-Date -Format 'yyyyMMdd-HHmmss')+'-'+[Guid]::NewGuid().ToString('N').Substring(0,8)
    $evidence=Join-Path $RepoRoot "qa/verification/day25/$runId"
    [void][IO.Directory]::CreateDirectory($evidence)
    $project='lasday25-'+$runId;$envFile=Join-Path $evidence '.env'
    $content=Get-Content (Join-Path $DeployRoot '.env.example') -Raw
    $overrides=@{MYSQL_DATABASE='day25_acceptance';MYSQL_USER='day25_app';RABBITMQ_USERNAME='day25_app';TRACING_ENABLED='true';TRACING_SAMPLING_PROBABILITY='1.0';APP_IMAGE='las-backend:day25'}
    $allocated=[Collections.Generic.HashSet[int]]::new()
    foreach($name in @('APP_A_PORT','APP_B_PORT','PROMETHEUS_PORT','GRAFANA_PORT','TEMPO_PORT','TEST_MYSQL_PORT','TEST_REDIS_PORT','TEST_RABBIT_PORT','TEST_RABBIT_MANAGEMENT_PORT')){
        do{$probe=[Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,0);$probe.Start();$port=$probe.LocalEndpoint.Port;$probe.Stop()}while(-not $allocated.Add($port))
        $overrides[$name]=[string]$port
    }
    foreach($name in @('MYSQL_PASSWORD','MYSQL_ROOT_PASSWORD','RABBITMQ_PASSWORD','METRICS_PASSWORD','GRAFANA_PASSWORD')){$overrides[$name]=[Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24))}
    $overrides.JWT_SECRET_BASE64=[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(48))
    foreach($name in $overrides.Keys){if($content -match "(?m)^$name="){$content=[regex]::Replace($content,"(?m)^$name=.*$",$name+'='+$overrides[$name])}else{$content+=[Environment]::NewLine+$name+'='+$overrides[$name]}}
    [IO.File]::WriteAllText($envFile,$content,[Text.UTF8Encoding]::new($false))
    Save 'context.json' @{project=$project;envFile=$envFile;runId=$runId;startedAt=[DateTimeOffset]::Now.ToString('o')}
}
$settings=Read-DeploymentEnvironment $envFile
$compose=(Get-ComposeArguments $project $envFile)+@('-f',(Join-Path $DeployRoot 'compose.acceptance.yaml'),'--profile','core','--profile','observability')
$appA='http://127.0.0.1:'+$settings.APP_A_PORT;$appB='http://127.0.0.1:'+$settings.APP_B_PORT
$prom='http://127.0.0.1:'+$settings.PROMETHEUS_PORT;$tempo='http://127.0.0.1:'+$settings.TEMPO_PORT
$grafana='http://127.0.0.1:'+$settings.GRAFANA_PORT;$broker='http://127.0.0.1:'+$settings.TEST_RABBIT_MANAGEMENT_PORT
Write-Output "Day25 $Stage evidence: $evidence"
$started=[DateTimeOffset]::Now;$passed=$false
try {
    if($Stage -in @('All','Start')){
        [void](Compose @('config','--quiet'))
        [void](Compose @('up','-d','--pull','never'))
        Wait-For 'both applications ready' { (Ready $appA) -and (Ready $appB) } 360
        Wait-For 'all Prometheus targets UP' {try{(Number 'sum(up{job="las-backend"})') -eq 2 -and (Number 'up{job="las-rabbitmq"}') -eq 1}catch{$false}} 120
        Save 'versions.json' @{docker=(Invoke-Program docker @('version','--format','{{.Server.Version}}')).Output.Trim();image=(Invoke-Program docker @('image','inspect','las-backend:day25','--format','{{.Id}}')).Output.Trim();flyway=(Sql 'SELECT version,description,success FROM flyway_schema_history ORDER BY installed_rank;')}
        Save 'endpoints.json' @{appA=$appA;appB=$appB;prometheus=$prom;grafana=$grafana;tempo=$tempo}
    }
    if($Stage -in @('All','Business')){. (Join-Path $PSScriptRoot 'day25-business.ps1')}
    if($Stage -in @('All','Observe')){. (Join-Path $PSScriptRoot 'day25-observe.ps1')}
    if($Stage -in @('All','Faults')){. (Join-Path $PSScriptRoot 'day25-faults.ps1')}
    if($Stage -in @('All','Reconcile')){Reconcile}
    $passed=$true
} finally {
    foreach($service in @('app-a','app-b')){
        try{(Compose @('logs','--no-color','--no-log-prefix',$service)) | Set-Content -LiteralPath (Join-Path $evidence "$service.log") -Encoding utf8}catch{}
    }
    Save "stage-$Stage.json" @{stage=$Stage;passed=$passed;startedAt=$started.ToString('o');endedAt=[DateTimeOffset]::Now.ToString('o');project=$project}
}
