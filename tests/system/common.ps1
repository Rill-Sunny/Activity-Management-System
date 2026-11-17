. (Join-Path $PSScriptRoot '../../deploy/scripts/common.ps1')
function Assert-Lab([bool]$condition,[string]$message){if(-not $condition){throw $message}}
function Save-Lab([string]$path,$value){$value|ConvertTo-Json -Depth 80|Set-Content -LiteralPath $path -Encoding utf8}
function Restore-LabEnvironment($previous){
    foreach($name in $previous.Keys){
        # PowerShell binds a null argument to String as empty; an empty process variable
        # overrides Compose --env-file values. Remove originally absent entries explicitly.
        if($null -eq $previous[$name]){Remove-Item -LiteralPath ('Env:'+$name) -ErrorAction SilentlyContinue}
        else{[Environment]::SetEnvironmentVariable($name,$previous[$name],'Process')}
    }
}
function Wait-Lab([string]$description,[scriptblock]$condition,[int]$seconds=120){
    $until=[DateTime]::UtcNow.AddSeconds($seconds)
    do{if(& $condition){return};Start-Sleep -Milliseconds 500}while([DateTime]::UtcNow -lt $until)
    throw "Timed out: $description"
}
function Open-Lab([string]$directory){
    $folder=[IO.Path]::GetFullPath($directory)
    $context=Get-Content -LiteralPath (Join-Path $folder 'context.json') -Raw|ConvertFrom-Json
    Assert-Lab ($context.project -match '^lasday(27|28|29|30)-[0-9]{8}-[0-9]{6}-[a-f0-9]{8}$') 'Only owned Day 27-30 projects are allowed'
    $settings=Read-DeploymentEnvironment $context.envFile
    $compose=(Get-ComposeArguments $context.project $context.envFile)+@('-f',(Join-Path $DeployRoot 'compose.acceptance.yaml'),'-f',(Join-Path $DeployRoot 'compose.day26-tests.yaml'),'-f',(Join-Path $DeployRoot 'compose.validation.yaml'),'--profile','core','--profile','observability')
    @{directory=$folder;context=$context;settings=$settings;compose=$compose;appA=('http://127.0.0.1:'+$settings.APP_A_PORT);appB=('http://127.0.0.1:'+$settings.APP_B_PORT);prom=('http://127.0.0.1:'+$settings.PROMETHEUS_PORT);broker=('http://127.0.0.1:'+$settings.TEST_RABBIT_MANAGEMENT_PORT)}
}
function Lab-Sql($lab,[string]$sql,[switch]$Root){Invoke-DatabaseSql -Compose $lab.compose -Sql $sql -Root:$Root -Database $(if($Root){$lab.settings.MYSQL_DATABASE}else{$null})}
function Lab-Compose($lab,[string[]]$arguments){(Invoke-Program docker ($lab.compose+$arguments)).Output}
function Lab-Api($lab,[string]$path,[string]$token='',$body=$null,[string]$method='POST',[switch]$B,[string]$requestId=''){
    $headers=@{};if($token){$headers.Authorization='Bearer '+$token};if($requestId){$headers['X-Request-ID']=$requestId}
    if($lab.context.day -eq 28){$headers.traceparent='00-'+[Guid]::NewGuid().ToString('N')+'-'+[Guid]::NewGuid().ToString('N').Substring(0,16)+'-01'}
    $base=if($B){$lab.appB}else{$lab.appA}
    $parameters=@{Uri=$base+$path;Method=$method;Headers=$headers;TimeoutSec=45;SkipHttpErrorCheck=$true;ContentType='application/json'}
    if($null -ne $body){$parameters.Body=$body|ConvertTo-Json -Depth 30 -Compress;$parameters.ContentType='application/json'}
    $response=Invoke-WebRequest @parameters
    $content=if($response.Content -is [byte[]]){[Text.Encoding]::UTF8.GetString($response.Content)}else{[string]$response.Content}
    $result=@{status=[int]$response.StatusCode;body=$(if($content){$content|ConvertFrom-Json}else{$null});requestId=($response.Headers['X-Request-ID'] -join '')}
    if($lab.context.day -eq 28){
        @{at=[DateTimeOffset]::Now.ToString('o');method=$method;path=$path;status=$result.status;requestId=$result.requestId;traceparent=$headers.traceparent}|ConvertTo-Json -Compress|Add-Content (Join-Path $lab.directory 'http-trace-index.jsonl')
    }
    $result
}
function Lab-Post($lab,[string]$path,[string]$token='',$body=$null,[switch]$B){
    $result=Lab-Api $lab $path $token $body -B:$B
    Assert-Lab ($result.status -in @(200,201,202,204)) "HTTP $path returned $($result.status): $($result.body|ConvertTo-Json -Depth 5 -Compress)"
    $result.body
}
function Lab-Reconcile($lab,[string]$destination,[string]$Database=''){
    [void][IO.Directory]::CreateDirectory($destination)
    $files=@(Get-ChildItem (Join-Path $RepoRoot 'tests/sql') -Filter '*.sql'|Sort-Object Name)
    $sql="SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ; START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY;`n"
    foreach($file in $files){$sql+="SELECT 'BEGIN:$($file.Name)';`n"+[IO.File]::ReadAllText($file.FullName)+"`nSELECT 'END:$($file.Name)';`n"}
    $text=if($Database){Invoke-DatabaseSql -Compose $lab.compose -Sql ($sql+'COMMIT;') -Root -Database $Database}else{Lab-Sql $lab ($sql+'COMMIT;')}
    $text|Set-Content (Join-Path $destination 'reconciliation.txt') -Encoding utf8
    $records=@();$differences=0
    foreach($file in $files){
        $match=[regex]::Match($text,'(?s)BEGIN:'+ [regex]::Escape($file.Name)+'\r?\n(.*?)\r?\n?END:'+ [regex]::Escape($file.Name))
        Assert-Lab $match.Success "Missing SQL result: $($file.Name)"
        $body=$match.Groups[1].Value.Trim();$diagnostic=$file.Name -eq 'messaging_reconciliation.sql'
        $count=if($diagnostic){$null}elseif($body){@($body -split '\r?\n').Count}else{0}
        if(-not $diagnostic){$differences+=$count}
        $records+=@{script=$file.Name;diagnostic=$diagnostic;differences=$count}
    }
    Save-Lab (Join-Path $destination 'reconciliation.json') $records
    Assert-Lab ($differences -eq 0) "Reconciliation has $differences differences; see $destination"
}
function Lab-Prom($lab,[string]$expression){
    $result=Invoke-RestMethod ($lab.prom+'/api/v1/query?query='+[Uri]::EscapeDataString($expression)) -TimeoutSec 15
    Assert-Lab ($result.status -eq 'success') 'Prometheus query failed';@($result.data.result)
}
