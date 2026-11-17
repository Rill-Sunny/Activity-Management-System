param([Parameter(Mandatory)][string]$RunDirectory,[Parameter(Mandatory)][string]$DemoDirectory,[switch]$ObserveOnly)
. (Join-Path $PSScriptRoot '../system/common.ps1')
$lab=Open-Lab $RunDirectory
Assert-Lab ($lab.context.day -in @(29,30)) 'Release verification requires an owned Day 29/30 project'
$output=Join-Path $lab.directory ('release-check-'+(Get-Date -Format 'HHmmss'))
[void][IO.Directory]::CreateDirectory($output)
$demo=Get-Content (Join-Path $DemoDirectory 'result.json') -Raw|ConvertFrom-Json
Assert-Lab $demo.passed 'Complete the real HTTP demonstration first'
function Ready {Wait-Lab 'both release applications healthy' {try{(Invoke-RestMethod ($lab.appA+'/actuator/health')).status -eq 'UP' -and (Invoke-RestMethod ($lab.appB+'/actuator/health')).status -eq 'UP'}catch{$false}} 240}
function Snapshot([string]$database){
    $tables=@{}
    foreach($name in ((Invoke-DatabaseSql -Compose $lab.compose -Sql 'SHOW TABLES;' -Root -Database $database) -split '\r?\n')){
        Assert-Lab ($name -match '^[a-z_]+$') 'Unexpected table name'
        $rows=Invoke-DatabaseSql -Compose $lab.compose -Sql "SELECT * FROM $name;" -Root -Database $database
        $canonical=(@($rows -split '\r?\n'|Sort-Object -CaseSensitive) -join "`n")
        $tables[$name]=@{rows=$(if($rows){@($rows -split '\r?\n').Count}else{0});sha256=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($canonical)))}
    };return $tables
}
function SameTables($before,$after){
    Assert-Lab ($before.Count -eq $after.Count) 'Table count changed'
    foreach($name in $before.Keys){Assert-Lab ($before[$name].sha256 -eq $after[$name].sha256) "Data differs: $name"}
}
Ready
$image=Invoke-Program docker @('image','inspect',$lab.context.image,'--format','{{json .Config.Labels}}')
$labels=$image.Output|ConvertFrom-Json
Assert-Lab ($labels.'org.opencontainers.image.revision' -eq $lab.context.sourceCommit) 'Image source label differs from lab context'
Assert-Lab ((Lab-Sql $lab 'SELECT VERSION();') -eq '8.4.11') 'Wrong MySQL version'
Assert-Lab ((Lab-Sql $lab 'SELECT COUNT(*) FROM flyway_schema_history WHERE success=1;') -eq '18') 'Missing migration'
Assert-Lab ((Lab-Sql $lab "SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND constraint_type='FOREIGN KEY';") -eq '0') 'Unexpected physical foreign key'
Save-Lab (Join-Path $output 'version.json') @{sourceCommit=$lab.context.sourceCommit;labels=$labels;java=(Lab-Compose $lab @('exec','-T','app-a','java','--version'));mysql='8.4.11';migrations=(Lab-Sql $lab 'SELECT version,checksum,success FROM flyway_schema_history ORDER BY installed_rank;');physicalForeignKeys=0}

Wait-Lab 'both Prometheus application targets up' {try{@(Lab-Prom $lab 'up{job="las-backend"}'|Where-Object{[double]$_.value[1] -eq 1}).Count -eq 2}catch{$false}} 120
$targets=Invoke-RestMethod ($lab.prom+'/api/v1/targets')
Save-Lab (Join-Path $output 'prometheus-targets.json') $targets
$grafana='http://127.0.0.1:'+$lab.settings.GRAFANA_PORT
$grafanaHeaders=@{Authorization='Basic '+[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes('admin:'+$lab.settings.GRAFANA_PASSWORD))}
$dashboard=Invoke-RestMethod ($grafana+'/api/dashboards/uid/las-backend-overview') -Headers $grafanaHeaders
Assert-Lab ($dashboard.dashboard.panels.Count -gt 0) 'Provisioned dashboard has no panels'
$sources=Invoke-RestMethod ($grafana+'/api/datasources') -Headers $grafanaHeaders
Assert-Lab (@($sources|Where-Object type -EQ 'prometheus').Count -gt 0 -and @($sources|Where-Object type -EQ 'tempo').Count -gt 0) 'Grafana datasources missing'
Save-Lab (Join-Path $output 'grafana.json') @{dashboardUid=$dashboard.dashboard.uid;panelCount=$dashboard.dashboard.panels.Count;datasources=@($sources|Select-Object name,type,uid)}
$steps=Get-Content (Join-Path $DemoDirectory 'steps.json') -Raw|ConvertFrom-Json
$traceId=($steps|Where-Object name -EQ 'Process lastRefund').detail.response.traceId
$traceHolder=@{}
Wait-Lab 'HTTP to MQ to AfterSale trace' {
    try{
        $trace=Invoke-RestMethod ('http://127.0.0.1:'+$lab.settings.TEMPO_PORT+'/api/traces/'+$traceId)
        $names=@($trace.batches.scopeSpans.spans.name)+@($trace.batches.instrumentationLibrarySpans.spans.name)
        $traceHolder.trace=$trace;$traceHolder.names=$names
        (@($names|Where-Object{$_ -match 'after.?sale'}).Count -gt 0 -and $names -contains 'outbox.publish' -and $names -contains 'mq.consume')
    }catch{$false}
} 120
Save-Lab (Join-Path $output 'business-trace.json') @{traceId=$traceId;names=$traceHolder.names;trace=$traceHolder.trace}

if(-not $ObserveOnly){
    $brokerHeaders=@{Authorization='Basic '+[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($lab.settings.RABBITMQ_USERNAME+':'+$lab.settings.RABBITMQ_PASSWORD))}
    $marker='release.persistence.'+[Guid]::NewGuid().ToString('N')
    Invoke-RestMethod ($lab.broker+'/api/queues/%2F/'+$marker) -Method Put -Headers $brokerHeaders -ContentType 'application/json' -Body '{"durable":true,"auto_delete":false,"arguments":{}}'|Out-Null
    $message=@{properties=@{delivery_mode=2};routing_key=$marker;payload=$marker;payload_encoding='string'}|ConvertTo-Json
    $published=Invoke-RestMethod ($lab.broker+'/api/exchanges/%2F/amq.default/publish') -Method Post -Headers $brokerHeaders -ContentType 'application/json' -Body $message
    Assert-Lab $published.routed 'Persistence marker was not routed'
    [void](Lab-Compose $lab @('exec','-T','redis','redis-cli','SET',$marker,'durable'))
    [void](Lab-Compose $lab @('stop','app-a','app-b'))
    try{
        $before=Snapshot $lab.settings.MYSQL_DATABASE;Save-Lab (Join-Path $output 'database-before.json') $before
        $backup=Join-Path $output 'backup.sql'
        & (Join-Path $DeployRoot 'scripts/backup-db.ps1') -Project $lab.context.project -EnvFile $lab.context.envFile -OutputFile $backup
        $restore='las_restore_'+[Guid]::NewGuid().ToString('N')
        & (Join-Path $DeployRoot 'scripts/restore-db.ps1') -Project $lab.context.project -EnvFile $lab.context.envFile -BackupFile $backup -TargetDatabase $restore
        $restored=Snapshot $restore;SameTables $before $restored
        Save-Lab (Join-Path $output 'database-restored.json') $restored
        Lab-Reconcile $lab (Join-Path $output 'restored') $restore
        [void](Lab-Compose $lab @('down','--timeout','35'))
        [void](Lab-Compose $lab @('up','-d','--pull','never','--no-build','mysql','redis','rabbitmq','migrate','prometheus','grafana','tempo'))
        Wait-Lab 'restored infrastructure readable' {try{(Lab-Sql $lab 'SELECT 1;') -eq '1'}catch{$false}} 180
        $after=Snapshot $lab.settings.MYSQL_DATABASE;SameTables $before $after
        Save-Lab (Join-Path $output 'database-after-restart.json') $after
        Assert-Lab ((Lab-Compose $lab @('exec','-T','redis','redis-cli','GET',$marker)).Trim() -eq 'durable') 'Redis AOF marker lost'
        $queueHolder=@{}
        Wait-Lab 'RabbitMQ durable queue restored' {try{$queueHolder.value=Invoke-RestMethod ($lab.broker+'/api/queues/%2F/'+$marker) -Headers $brokerHeaders;$queueHolder.value.durable -and $queueHolder.value.messages -eq 1}catch{$false}} 90
        Save-Lab (Join-Path $output 'persistence.json') @{tables=$before.Count;databaseDifferences=0;restoreDatabase=$restore;redisMarkerRetained=$true;rabbitQueueDurable=$true;rabbitMessages=$queueHolder.value.messages;volumesDeleted=$false}
        # Re-run the same image's migration command: validate existing checksums, no new DDL.
        [void](Lab-Compose $lab @('run','--rm','--no-deps','migrate'))
        Assert-Lab ((Lab-Sql $lab 'SELECT COUNT(*) FROM flyway_schema_history WHERE success=1;') -eq '18') 'Repeated Flyway run changed history'
        Invoke-RestMethod ($lab.broker+'/api/queues/%2F/'+$marker) -Method Delete -Headers $brokerHeaders|Out-Null
    }finally{[void](Lab-Compose $lab @('up','-d','--pull','never','--no-build','app-a','app-b'));Ready}
}
Lab-Reconcile $lab (Join-Path $output 'final')
Save-Lab (Join-Path $output 'result.json') @{passed=$true;sourceCommit=$lab.context.sourceCommit;image=$lab.context.image;observeOnly=[bool]$ObserveOnly;at=[DateTimeOffset]::Now.ToString('o');databaseDifferences=0}
Write-Output "RELEASE_CHECK=$output"
