param([ValidateSet(27,28,29,30)][int]$Day,[string]$Image='las-backend:day26',[Parameter(Mandatory)][string]$SourceCommit)
. (Join-Path $PSScriptRoot 'common.ps1')
$runId=(Get-Date -Format 'yyyyMMdd-HHmmss')+'-'+[Guid]::NewGuid().ToString('N').Substring(0,8)
$evidence=Join-Path $RepoRoot "qa/verification/day$Day/$runId";[void][IO.Directory]::CreateDirectory($evidence)
$project="lasday$Day-$runId";$envFile=Join-Path $evidence '.env'
$content=Get-Content (Join-Path $DeployRoot '.env.example') -Raw
$values=@{MYSQL_DATABASE="day${Day}_validation";MYSQL_USER="day${Day}_app";RABBITMQ_USERNAME="day${Day}_app";APP_IMAGE=$Image;TRACING_ENABLED='true';TRACING_SAMPLING_PROBABILITY=$(if($Day -ge 28){'1.0'}else{'0.1'})}
$ports=[Collections.Generic.HashSet[int]]::new()
foreach($name in @('APP_A_PORT','APP_B_PORT','PROMETHEUS_PORT','GRAFANA_PORT','TEMPO_PORT','TEST_MYSQL_PORT','TEST_REDIS_PORT','TEST_RABBIT_PORT','TEST_RABBIT_MANAGEMENT_PORT','TEST_OTLP_PORT')){
    do{$socket=[Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,0);$socket.Start();$port=$socket.LocalEndpoint.Port;$socket.Stop()}while(-not $ports.Add($port));$values[$name]=[string]$port
}
foreach($name in @('MYSQL_PASSWORD','MYSQL_ROOT_PASSWORD','RABBITMQ_PASSWORD','METRICS_PASSWORD','GRAFANA_PASSWORD')){$values[$name]=[Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24))}
$values.JWT_SECRET_BASE64=[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(48))
foreach($name in $values.Keys){if($content -match "(?m)^$name="){$content=[regex]::Replace($content,"(?m)^$name=.*$",$name+'='+$values[$name])}else{$content+=[Environment]::NewLine+$name+'='+$values[$name]}}
[IO.File]::WriteAllText($envFile,$content,[Text.UTF8Encoding]::new($false))
Save-Lab (Join-Path $evidence 'context.json') @{project=$project;envFile=$envFile;day=$Day;sourceCommit=$SourceCommit;image=$Image;createdAt=[DateTimeOffset]::Now.ToString('o')}
$lab=Open-Lab $evidence
[void](Lab-Compose $lab @('config','--quiet'));[void](Lab-Compose $lab @('up','-d','--pull','never','--no-build'))
Wait-Lab 'both applications ready' {try{(Invoke-RestMethod ($lab.appA+'/actuator/health')).status -eq 'UP' -and (Invoke-RestMethod ($lab.appB+'/actuator/health')).status -eq 'UP'}catch{$false}} 360
Wait-Lab 'Tempo replay and readiness' {try{(Invoke-WebRequest ('http://127.0.0.1:'+$lab.settings.TEMPO_PORT+'/ready') -TimeoutSec 5).StatusCode -eq 200}catch{$false}} 180
$cpu=Get-CimInstance Win32_Processor;$os=Get-CimInstance Win32_OperatingSystem
Save-Lab (Join-Path $evidence 'environment.json') @{cpu=@($cpu|Select-Object Name,NumberOfCores,NumberOfLogicalProcessors);memoryBytes=[long]$os.TotalVisibleMemorySize*1024;os=$os.Caption;docker=(Invoke-Program docker @('info','--format','{{json .}}')).Output|ConvertFrom-Json|Select-Object ServerVersion,NCPU,MemTotal,KernelVersion,OperatingSystem;java=(Invoke-Program docker ($lab.compose+@('exec','-T','app-a','java','-version'))).Error;mysql=(Lab-Sql $lab 'SELECT VERSION();');mysqlSettings=(Lab-Sql $lab "SHOW VARIABLES WHERE Variable_name IN ('max_connections','innodb_buffer_pool_size','transaction_isolation','innodb_lock_wait_timeout','innodb_deadlock_detect','slow_query_log','long_query_time');");redis=(Lab-Compose $lab @('exec','-T','redis','redis-server','--version'));rabbitmq=(Lab-Compose $lab @('exec','-T','rabbitmq','rabbitmq-diagnostics','-q','server_version'));image=(Invoke-Program docker @('image','inspect',$Image,'--format','{{.Id}}')).Output;applicationInstances=2;heap='-Xms256m -Xmx640m';gc='G1GC';hikariMaximumPerInstance=10;sourceCommit=$SourceCommit;flyway=(Lab-Sql $lab 'SELECT version,success FROM flyway_schema_history ORDER BY installed_rank;')}
Assert-Lab ((Lab-Sql $lab 'SELECT VERSION();') -eq '8.4.11') 'Expected MySQL 8.4.11'
Lab-Reconcile $lab (Join-Path $evidence 'initial')
Write-Output "LAB_DIRECTORY=$evidence"
