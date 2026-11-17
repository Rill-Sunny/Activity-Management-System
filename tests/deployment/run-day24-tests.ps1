param(
    [ValidateSet('All','Start','Business','Recovery','Observe')][string]$Stage='All',
    [string]$RunDirectory
)
. (Join-Path $PSScriptRoot '../../deploy/scripts/common.ps1')
function Save-Json([string]$name,$value){$value | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath (Join-Path $evidence $name) -Encoding utf8}
function Check([bool]$condition,[string]$message){if(-not $condition){throw $message}}
function Run-Compose([string[]]$Arguments,[string]$Log){
    $result=Invoke-Program docker ($compose+$Arguments)
    if($Log){($result.Output+$result.Error) | Set-Content -LiteralPath (Join-Path $evidence $Log) -Encoding utf8}
    return $result.Output
}
function Wait-Ready {
    $deadline=[DateTime]::UtcNow.AddMinutes(5)
    do {
        $ready=$true
        foreach($port in @($settings.APP_A_PORT,$settings.APP_B_PORT)){
            try{$response=Invoke-RestMethod "http://127.0.0.1:$port/actuator/health" -TimeoutSec 5; if($response.status -ne 'UP'){$ready=$false}}
            catch{$ready=$false}
        }
        if($ready){return}
        Start-Sleep -Seconds 3
    }while([DateTime]::UtcNow -lt $deadline)
    throw 'Both application instances did not become ready within five minutes'
}
function Reconcile([string]$label,[string]$Database){
    $files=@(Get-ChildItem -LiteralPath (Join-Path $RepoRoot 'tests/sql') -Filter '*.sql' | Sort-Object Name)
    $sql="SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ; START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY;`n"
    foreach($file in $files){$sql+="SELECT 'BEGIN:$($file.Name)';`n"+[IO.File]::ReadAllText($file.FullName)+"`nSELECT 'END:$($file.Name)';`n"}
    $sql+='COMMIT;'
    $text=if($Database){Invoke-DatabaseSql $compose $sql -Root -Database $Database}else{Invoke-DatabaseSql $compose $sql}
    $text | Set-Content -LiteralPath (Join-Path $evidence "$label-reconciliation.txt") -Encoding utf8
    $report=@()
    foreach($file in $files){
        $pattern='(?s)BEGIN:'+ [regex]::Escape($file.Name)+'\r?\n(.*?)\r?\n?END:'+ [regex]::Escape($file.Name)
        $match=[regex]::Match($text,$pattern)
        Check $match.Success "Missing reconciliation output: $($file.Name)"
        $rows=$match.Groups[1].Value.Trim()
        $diagnostic=$file.Name -eq 'messaging_reconciliation.sql'
        $report+=@{script=$file.Name;diagnostic=$diagnostic;differences=if($diagnostic){$null}elseif($rows){@($rows -split "\r?\n").Count}else{0}}
        if(-not $diagnostic){Check (-not $rows) "Database differences in $($file.Name): $rows"}
    }
    Save-Json "$label-reconciliation.json" $report
}
function Snapshot([string]$database){
    $tables=if($database){Invoke-DatabaseSql $compose 'SHOW TABLES;' -Root -Database $database}else{Invoke-DatabaseSql $compose 'SHOW TABLES;'}
    $snapshot=@{}
    foreach($table in ($tables -split "\r?\n")){
        Check ($table -match '^[a-z_]+$') 'Unexpected table name'
        $query="SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='$table' AND INDEX_NAME='PRIMARY';"
        $keys=if($database){Invoke-DatabaseSql $compose $query -Root -Database $database}else{Invoke-DatabaseSql $compose $query}
        Check ($keys -match '^[a-z_,]+$') "No stable primary key: $table"
        $query="SELECT * FROM $table ORDER BY $keys;"
        $rows=if($database){Invoke-DatabaseSql $compose $query -Root -Database $database}else{Invoke-DatabaseSql $compose $query}
        $snapshot[$table]=@{sha256=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($rows)));rows=if($rows){@($rows -split "\r?\n").Count}else{0}}
    }
    return $snapshot
}

if($RunDirectory){
    $evidence=[IO.Path]::GetFullPath($RunDirectory)
    $context=Get-Content -LiteralPath (Join-Path $evidence 'context.json') -Raw | ConvertFrom-Json
    $project=$context.project; $envFile=$context.envFile
}else{
    Check ($Stage -in @('All','Start')) 'A later stage requires -RunDirectory'
    $runId=(Get-Date -Format 'yyyyMMdd-HHmmss')+'-'+[Guid]::NewGuid().ToString('N').Substring(0,8)
    $evidence=Join-Path $RepoRoot "qa/verification/day24/$runId"
    [void][IO.Directory]::CreateDirectory($evidence)
    $project='lasday24-'+$runId
    $envFile=Join-Path $evidence '.env'
    $content=Get-Content -LiteralPath (Join-Path $DeployRoot '.env.example') -Raw
    $overrides=@{MYSQL_DATABASE='day24_acceptance';MYSQL_USER='day24_app';RABBITMQ_USERNAME='day24_app';TRACING_ENABLED='true'}
    $allocatedPorts=[Collections.Generic.HashSet[int]]::new()
    foreach($name in @('APP_A_PORT','APP_B_PORT','PROMETHEUS_PORT','GRAFANA_PORT','TEMPO_PORT',
            'TEST_MYSQL_PORT','TEST_REDIS_PORT','TEST_RABBIT_PORT','TEST_RABBIT_MANAGEMENT_PORT')){
        do {$probe=[Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,0);$probe.Start();$port=$probe.LocalEndpoint.Port;$probe.Stop()}while(-not $allocatedPorts.Add($port))
        $overrides[$name]=[string]$port
    }
    foreach($key in @('MYSQL_PASSWORD','MYSQL_ROOT_PASSWORD','RABBITMQ_PASSWORD','METRICS_PASSWORD','GRAFANA_PASSWORD')){
        $overrides[$key]=[Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24))
    }
    $overrides.JWT_SECRET_BASE64=[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(48))
    foreach($key in $overrides.Keys){
        if($content -match "(?m)^$key="){$content=[regex]::Replace($content,"(?m)^$key=.*$",$key+'='+$overrides[$key])}
        else{$content+="`n$key=$($overrides[$key])"}
    }
    [IO.File]::WriteAllText($envFile,$content,[Text.UTF8Encoding]::new($false))
    Save-Json 'context.json' @{project=$project;envFile=$envFile;runId=$runId;startedAt=[DateTimeOffset]::Now.ToString('o')}
}
$settings=Read-DeploymentEnvironment $envFile
$compose=(Get-ComposeArguments $project $envFile)+@('-f',(Join-Path $DeployRoot 'compose.acceptance.yaml'),'--profile','core','--profile','observability')
Write-Output "Day24 $Stage evidence: $evidence"
$success=$false
try {
    if($Stage -in @('All','Start')){
        [void](Run-Compose @('--profile','core','--profile','observability','config','--quiet') 'compose-config.log')
        $resolved=(Run-Compose @('--profile','core','--profile','observability','config','--format','json')) | ConvertFrom-Json
        $topology=@{}
        foreach($property in $resolved.services.PSObject.Properties){
            $service=$property.Value
            $topology[$property.Name]=@{image=$service.image;profiles=$service.profiles;ports=$service.ports;volumes=$service.volumes;depends_on=$service.depends_on}
        }
        Save-Json 'topology.json' $topology
        # Negative configuration check: never print resolved secrets.
        $missing=Join-Path $evidence '.env.missing'
        [IO.File]::WriteAllText($missing,([IO.File]::ReadAllText($envFile) -replace '(?m)^MYSQL_PASSWORD=.*\r?\n',''))
        $negative=Invoke-Program docker ((Get-ComposeArguments $project $missing)+@('--profile','core','config','--quiet')) -AllowFailure
        Check ($negative.ExitCode -ne 0 -and $negative.Error -match 'MYSQL_PASSWORD') 'Missing password did not fail clearly'
        Save-Json 'missing-env.json' @{exitCode=$negative.ExitCode;error=$negative.Error}
        $coreEnvironment=Join-Path $evidence '.env.core'
        [IO.File]::WriteAllText($coreEnvironment,([IO.File]::ReadAllText($envFile) -replace '(?m)^TRACING_ENABLED=.*$','TRACING_ENABLED=false'))
        $coreArguments=(Get-ComposeArguments $project $coreEnvironment)+@('-f',(Join-Path $DeployRoot 'compose.acceptance.yaml'),'--profile','core')
        $coreStart=Invoke-Program docker ($coreArguments+@('up','-d','--pull','never'))
        ($coreStart.Output+$coreStart.Error) | Set-Content -LiteralPath (Join-Path $evidence 'core-up.log') -Encoding utf8
        Wait-Ready
        $coreServices=(Invoke-Program docker ($coreArguments+@('ps','--services','--status','running'))).Output -split "\r?\n" | Where-Object {$_}
        Check (@($coreServices | Where-Object {$_ -in @('grafana','prometheus','tempo')}).Count -eq 0) 'Observability started with core only'
        Check (@($coreServices).Count -eq 5) 'Core must run two apps and three middleware services'
        Save-Json 'core-only.json' @{running=$coreServices;health='both UP';migration='completed separately'}
        [void](Run-Compose @('--profile','core','--profile','observability','up','-d','--pull','never') 'up.log')
        Wait-Ready
        [void](Run-Compose @('ps','--all','--format','json') 'compose-ps-start.json')
        [void](Run-Compose @('logs','--no-color','migrate') 'flyway.log')
        [void](Run-Compose @('exec','-T','app-a','java','-version') 'java-version.log')
        [void](Run-Compose @('exec','-T','app-a','id') 'application-user.log')
        $version=Invoke-DatabaseSql $compose 'SELECT VERSION();'
        Check ($version -match '^8\.4\.11') "Unexpected MySQL version: $version"
        $migration=Invoke-DatabaseSql $compose 'SELECT version,description,success FROM flyway_schema_history ORDER BY installed_rank;'
        Check (($migration -split "\r?\n").Count -eq 18) 'Not all 18 migrations were applied'
        Save-Json 'database-version.json' @{version=$version;migrations=$migration}
    }
    if($Stage -in @('All','Business')){
        & (Join-Path $PSScriptRoot 'day24-business.ps1') -Compose $compose -AppA "http://127.0.0.1:$($settings.APP_A_PORT)" -AppB "http://127.0.0.1:$($settings.APP_B_PORT)" -Settings $settings -Evidence $evidence -BrokerUrl "http://127.0.0.1:$($settings.TEST_RABBIT_MANAGEMENT_PORT)"
        Reconcile 'business' ''
    }
    if($Stage -in @('All','Recovery')){
        [void](Run-Compose @('stop','app-a','app-b') 'pause-writers.log')
        $before=Snapshot ''
        Save-Json 'database-before.json' $before
        [void](Run-Compose @('exec','-T','redis','redis-cli','SET','day24:persistence','durable-marker') 'redis-marker.log')
        $backup=Join-Path $evidence 'backup.sql'
        & (Join-Path $DeployRoot 'scripts/backup-db.ps1') -Project $project -EnvFile $envFile -OutputFile $backup
        $restore='las_restore_'+[Guid]::NewGuid().ToString('N')
        & (Join-Path $DeployRoot 'scripts/restore-db.ps1') -Project $project -EnvFile $envFile -BackupFile $backup -TargetDatabase $restore
        $restored=Snapshot $restore
        foreach($table in $before.Keys){Check ($before[$table].sha256 -eq $restored[$table].sha256) "Restore row hash mismatch: $table"}
        Check ($before.Count -eq $restored.Count) 'Restore table count differs'
        Save-Json 'restore-comparison.json' @{database=$restore;differences=0;tables=$restored}
        Reconcile 'restored' $restore
        [void](Run-Compose @('--profile','core','--profile','observability','down','--timeout','35') 'down.log')
        [void](Run-Compose @('--profile','core','--profile','observability','up','-d','--pull','never','mysql','redis','rabbitmq','migrate','prometheus','grafana','tempo') 'restart-infrastructure.log')
        # Wait on real MySQL and Redis readiness before reading restored volumes.
        $deadline=[DateTime]::UtcNow.AddMinutes(3)
        do {try{$after=Snapshot '';break}catch{if([DateTime]::UtcNow -ge $deadline){throw};Start-Sleep -Seconds 3}}while($true)
        foreach($table in $before.Keys){Check ($before[$table].sha256 -eq $after[$table].sha256) "Restart row hash mismatch: $table"}
        $redis=(Run-Compose @('exec','-T','redis','redis-cli','GET','day24:persistence')).Trim()
        Check ($redis -eq 'durable-marker') 'Redis AOF marker was lost'
        $basic=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($settings.RABBITMQ_USERNAME+':'+$settings.RABBITMQ_PASSWORD))
        $queue=Invoke-RestMethod "http://127.0.0.1:$($settings.TEST_RABBIT_MANAGEMENT_PORT)/api/queues/%2F/day24.persistence" -Headers @{Authorization='Basic '+$basic}
        Check ($queue.durable -and $queue.messages -eq 1) 'Rabbit persistent queue/message was lost'
        Save-Json 'persistence.json' @{mysqlTableDifferences=0;redisMarker=$redis;rabbitDurable=$queue.durable;rabbitMessages=$queue.messages}
        [void](Run-Compose @('--profile','core','--profile','observability','up','-d','--pull','never') 'restart-applications.log')
        Wait-Ready
        Reconcile 'restart' ''
    }
    if($Stage -in @('All','Observe')){
        $deadline=[DateTime]::UtcNow.AddMinutes(2)
        do {
            try{$targets=Invoke-RestMethod "http://127.0.0.1:$($settings.PROMETHEUS_PORT)/api/v1/targets";if(@($targets.data.activeTargets | Where-Object health -EQ 'up').Count -eq 2){break}}catch{}
            Start-Sleep -Seconds 3
        }while([DateTime]::UtcNow -lt $deadline)
        Check (@($targets.data.activeTargets | Where-Object health -EQ 'up').Count -eq 2) 'Prometheus does not scrape both instances'
        Save-Json 'prometheus-targets.json' $targets
        $grafana=Invoke-RestMethod "http://127.0.0.1:$($settings.GRAFANA_PORT)/api/health"
        Check ($grafana.database -eq 'ok') 'Grafana database not ready'
        Save-Json 'grafana-health.json' $grafana
        $deadline=[DateTime]::UtcNow.AddMinutes(2)
        do {
            try{$tempo=Invoke-WebRequest "http://127.0.0.1:$($settings.TEMPO_PORT)/ready" -TimeoutSec 5;break}
            catch{if([DateTime]::UtcNow -ge $deadline){throw};Start-Sleep -Seconds 3}
        }while($true)
        Check ($tempo.StatusCode -eq 200) 'Tempo not ready'
        Save-Json 'tempo-ready.json' @{status=$tempo.StatusCode;body=$tempo.Content}
        $traceId=[Guid]::NewGuid().ToString('N')
        [void](Invoke-WebRequest "http://127.0.0.1:$($settings.APP_A_PORT)/activities/240001" -Headers @{traceparent="00-$traceId-1234567890abcdef-01"})
        $deadline=[DateTime]::UtcNow.AddMinutes(1)
        do {try{$trace=Invoke-RestMethod "http://127.0.0.1:$($settings.TEMPO_PORT)/api/traces/$traceId";break}catch{if([DateTime]::UtcNow -ge $deadline){throw};Start-Sleep -Seconds 3}}while($true)
        Save-Json 'tempo-trace.json' @{traceId=$traceId;trace=$trace}
        $boundary=@{}
        foreach($probe in @(
            @{name='unauthenticated';url='http://127.0.0.1:9091/actuator/prometheus';auth='';expected=401},
            @{name='internal-authenticated';url='http://127.0.0.1:9091/actuator/prometheus';auth='--auth-no-challenge --user=prometheus --password="$METRICS_PASSWORD"';expected=200},
            @{name='public-port';url='http://127.0.0.1:8080/actuator/prometheus';auth='--auth-no-challenge --user=prometheus --password="$METRICS_PASSWORD"';expected=403}
        )){
            $response=Invoke-Program docker ($compose+@('exec','-T','app-a','sh','-c',('wget -q -S '+$probe.auth+' -O /dev/null '+$probe.url))) -AllowFailure
            $codes=@([regex]::Matches($response.Error,'HTTP/1\.1 (\d+)') | ForEach-Object {[int]$_.Groups[1].Value})
            Check ($codes.Count -gt 0 -and $codes[-1] -eq $probe.expected) "Unexpected metrics boundary: $($probe.name)"
            $boundary[$probe.name]=$codes[-1]
        }
        Save-Json 'metrics-security.json' $boundary
        $instances=@()
        foreach($name in @('app-a','app-b')){
            $id=(Run-Compose @('ps','-q',$name)).Trim()
            $inspection=(Invoke-Program docker @('inspect',$id)).Output | ConvertFrom-Json
            $instances+=@{name=$name;image=$inspection[0].Image;pid=$inspection[0].State.Pid;user=$inspection[0].Config.User}
        }
        Check ($instances[0].image -eq $instances[1].image -and $instances[0].pid -ne $instances[1].pid) 'Instances must share image but use different processes'
        Check ($instances[0].user -eq '10001:10001' -and $instances[1].user -eq '10001:10001') 'Application runs as unexpected user'
        Save-Json 'instances.json' $instances
    }
    $success=$true
} finally {
    try{[void](Run-Compose @('logs','--no-color','--tail','2000') "$Stage-compose.log")}catch{Write-Warning 'Could not collect Compose logs'}
    Save-Json "$Stage-summary.json" @{stage=$Stage;passed=$success;finishedAt=[DateTimeOffset]::Now.ToString('o');project=$project;gitHead=(& git -C $RepoRoot rev-parse HEAD)}
}
