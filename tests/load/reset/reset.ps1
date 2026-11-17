param([Parameter(Mandatory)][string]$RunDirectory)
. (Join-Path $PSScriptRoot '../../system/common.ps1')
$lab=Open-Lab $RunDirectory
Assert-Lab ($lab.settings.MYSQL_DATABASE -match '^day(27|28|29)_validation$') 'Refuse to reset another database; final Day 30 demo is retained'
Assert-Lab ((Lab-Sql $lab 'SELECT DATABASE();') -eq $lab.settings.MYSQL_DATABASE) 'Database identity mismatch'
[void](Lab-Compose $lab @('stop','app-a','app-b'))
$auth=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($lab.settings.RABBITMQ_USERNAME+':'+$lab.settings.RABBITMQ_PASSWORD))
$headers=@{Authorization='Basic '+$auth}
$queues=Invoke-RestMethod ($lab.broker+'/api/queues/%2F') -Headers $headers
foreach($queue in $queues){Invoke-RestMethod ($lab.broker+'/api/queues/%2F/'+[Uri]::EscapeDataString($queue.name)+'/contents') -Method Delete -Headers $headers|Out-Null}
$tables=@((Lab-Sql $lab "SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE() AND table_type='BASE TABLE' AND table_name<>'flyway_schema_history';") -split '\r?\n')
$statements='';foreach($table in $tables){Assert-Lab ($table -match '^[a-z_]+$') 'Unexpected table identifier';$statements+="TRUNCATE TABLE $table;`n"}
[void](Lab-Sql $lab $statements)
[void](Lab-Compose $lab @('exec','-T','redis','redis-cli','FLUSHDB'))
[void](Lab-Sql $lab 'TRUNCATE TABLE mysql.slow_log;' -Root)
[void](Lab-Compose $lab @('start','app-a','app-b'))
Wait-Lab 'reset applications ready' {try{(Invoke-RestMethod ($lab.appA+'/actuator/health')).status -eq 'UP' -and (Invoke-RestMethod ($lab.appB+'/actuator/health')).status -eq 'UP'}catch{$false}} 180
