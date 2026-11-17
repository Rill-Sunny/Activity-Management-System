param([Parameter(Mandatory)][string]$RunDirectory)
. (Join-Path $PSScriptRoot 'common.ps1')
$lab=Open-Lab $RunDirectory;Assert-Lab ($lab.context.day -eq 28) 'Restore experiment requires Day 28 isolation'
$output=Join-Path $lab.directory ('backup-restore-'+(Get-Date -Format HHmmss));[void][IO.Directory]::CreateDirectory($output)
function Snapshot([string]$database){
    $names=Invoke-DatabaseSql -Compose $lab.compose -Sql 'SHOW TABLES;' -Root -Database $database
    $tables=@{};$count=0
    foreach($name in ($names -split '\r?\n')){
        Assert-Lab ($name -match '^[a-z_]+$') 'Unexpected table identifier'
        $rows=Invoke-DatabaseSql -Compose $lab.compose -Sql "SELECT * FROM $name;" -Root -Database $database
        $canonical=(@($rows -split '\r?\n'|Sort-Object -CaseSensitive) -join "`n")
        $tables[$name]=@{rows=$(if($rows){@($rows -split '\r?\n').Count}else{0});sha256=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($canonical)))};$count++
    };$tables
}
function Ready{Wait-Lab 'original applications ready' {try{(Invoke-RestMethod ($lab.appA+'/actuator/health')).status -eq 'UP' -and (Invoke-RestMethod ($lab.appB+'/actuator/health')).status -eq 'UP'}catch{$false}} 180}
$containerNames=[Collections.Generic.List[string]]::new()
try{
    Wait-Lab 'all writers converged' {(Lab-Sql $lab "SELECT (SELECT COUNT(*) FROM outbox_event WHERE status<>'SENT')+(SELECT COUNT(*) FROM cache_invalidation_task WHERE completed_at IS NULL)+(SELECT COUNT(*) FROM cancellation_batch WHERE status<>'COMPLETED');") -eq '0'} 180
    Wait-Lab 'immediate business events consumed before backup' {(Lab-Sql $lab "SELECT COUNT(*) FROM outbox_event e WHERE e.event_type<>'ORDER_EXPIRED' AND NOT EXISTS (SELECT 1 FROM consumer_process p WHERE p.event_id=e.event_id);") -eq '0'} 120
    [void](Lab-Compose $lab @('stop','app-a','app-b'))
    Lab-Reconcile $lab (Join-Path $output 'source-before')
    $before=Snapshot $lab.settings.MYSQL_DATABASE;Save-Lab (Join-Path $output 'source-snapshot.json') $before
    $backup=Join-Path $output 'backup.sql';$start=[DateTimeOffset]::Now
    & (Join-Path $DeployRoot 'scripts/backup-db.ps1') -Project $lab.context.project -EnvFile $lab.context.envFile -OutputFile $backup
    $backupSeconds=([DateTimeOffset]::Now-$start).TotalSeconds
    # A real subsequent purchase demonstrates that this snapshot cannot provide zero RPO.
    [void](Lab-Compose $lab @('start','app-a','app-b'));Ready
    $fixture=Get-ChildItem $lab.directory -Directory -Filter 'F07-*'|Sort-Object Name|Select-Object -Last 1
    Assert-Lab ($null -ne $fixture) 'Run the cancellation recovery fixture before backup'
    $f=Get-Content (Join-Path $fixture.FullName 'fixture.private.json') -Raw|ConvertFrom-Json
    $order=Lab-Post $lab '/product-orders/confirm' $f.tokens[0] @{requestId=('after-backup-'+[Guid]::NewGuid().ToString('N'));items=@(@{skuId=$f.skuA;quantity=1})}
    $paid=Lab-Post $lab "/product-orders/$($order.orderId)/pay" $f.tokens[0]
    Assert-Lab ($paid.status -eq 'PAID') 'Post-backup purchase failed'
    $afterBackupTrade=@{orderId=$order.orderId;status=$paid.status;at=[DateTimeOffset]::Now.ToString('o')}
    [void](Lab-Compose $lab @('stop','app-a','app-b'))
    $database='las_restore_'+[Guid]::NewGuid().ToString('N');$restoreStart=[DateTimeOffset]::Now
    & (Join-Path $DeployRoot 'scripts/restore-db.ps1') -Project $lab.context.project -EnvFile $lab.context.envFile -BackupFile $backup -TargetDatabase $database
    $restoreSeconds=([DateTimeOffset]::Now-$restoreStart).TotalSeconds
    $restored=Snapshot $database
    Assert-Lab ($before.Count -eq $restored.Count) 'Restore table count differs'
    foreach($name in $before.Keys){Assert-Lab ($before[$name].sha256 -eq $restored[$name].sha256) "Restore data differs in $name"}
    Save-Lab (Join-Path $output 'restored-snapshot.json') $restored
    Lab-Reconcile $lab (Join-Path $output 'restored') $database
    Assert-Lab ((Invoke-DatabaseSql -Compose $lab.compose -Sql "SELECT COUNT(*) FROM product_order WHERE order_id=$($order.orderId);" -Root -Database $database) -eq '0') 'Post-backup trade unexpectedly exists in snapshot'
    Assert-Lab ($lab.settings.MYSQL_USER -match '^day28_app$') 'Unexpected restore grant principal'
    [void](Lab-Sql $lab "GRANT ALL PRIVILEGES ON $database.* TO '$($lab.settings.MYSQL_USER)'@'%';" -Root)
    $id=(Lab-Compose $lab @('ps','-a','-q','app-a')).Trim()
    $envText=(Invoke-Program docker @('inspect',$id,'--format','{{range .Config.Env}}{{println .}}{{end}}')).Output
    $url="jdbc:mysql://mysql:3306/${database}?connectionTimeZone=Asia/Shanghai&forceConnectionTimeZoneToSession=true&allowPublicKeyRetrieval=true&useSSL=false"
    $envText=[regex]::Replace($envText,'(?m)^SPRING_DATASOURCE_URL=.*$','SPRING_DATASOURCE_URL='+$url)
    $envText=[regex]::Replace($envText,'(?m)^INSTANCE_ID=.*$','INSTANCE_ID=restore')
    $envText+="`nCACHE_REDIS_KEY_PREFIX=restore:$database`:`nMESSAGING_RABBIT_NAME_PREFIX=restore.$database.`n"
    $private=Join-Path $output '.env.restore';[IO.File]::WriteAllText($private,$envText,[Text.UTF8Encoding]::new($false))
    $validation=@()
    foreach($version in @(@{name='current';image=$lab.context.image},@{name='rollback-day26';image='las-backend:day26'})){
        $migrate=Invoke-Program docker @('run','--rm','--pull','never','--network',($lab.context.project+'_backend'),'--env-file',$private,$version.image,'migrate') -OutputFile (Join-Path $output ($version.name+'-flyway.log'))
        Assert-Lab ($migrate.ExitCode -eq 0) 'Restored Flyway validation failed'
        $socket=[Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,0);$socket.Start();$port=$socket.LocalEndpoint.Port;$socket.Stop()
        $name=$lab.context.project+'-restore-'+$version.name;$containerNames.Add($name)
        [void](Invoke-Program docker @('run','-d','--pull','never','--name',$name,'--network',($lab.context.project+'_backend'),'--env-file',$private,'-p',"127.0.0.1:${port}:8080",$version.image))
        $base="http://127.0.0.1:$port";Wait-Lab 'restored version ready' {try{(Invoke-RestMethod ($base+'/actuator/health')).status -eq 'UP'}catch{$false}} 180
        $product=Invoke-RestMethod ($base+"/products/$($f.productId)")
        $notice=Invoke-RestMethod ($base+"/sessions/$($f.sessionId)/cancellation")
        Assert-Lab ($product.productId -eq $f.productId) 'Restored Product query failed'
        Assert-Lab ($null -ne $notice) 'Restored cancellation history query failed'
        Lab-Reconcile $lab (Join-Path $output ($version.name+'-after-start')) $database
        $validation+=@{version=$version.name;image=$version.image;health='UP';productId=$product.productId;cancellation=$notice;databaseDifferences=0}
        [void](Invoke-Program docker @('stop',$name))
        (Invoke-Program docker @('logs',$name)).Output|Set-Content (Join-Path $output ($version.name+'-application.log'))
    }
    Save-Lab (Join-Path $output 'result.json') @{passed=$true;sourceCommit=$lab.context.sourceCommit;sourceDatabase=$lab.settings.MYSQL_DATABASE;restoredDatabase=$database;backupSeconds=$backupSeconds;restoreSeconds=$restoreSeconds;recoveryValidationSeconds=([DateTimeOffset]::Now-$restoreStart).TotalSeconds;backupMetadata=(Get-Content ($backup+'.json') -Raw|ConvertFrom-Json);tablesCompared=$before.Count;tableHashDifferences=0;sqlDifferences=0;postBackupTrade=$afterBackupTrade;postBackupTradePresentInRestore=$false;versions=$validation;zeroDataLossClaim=$false}
    Write-Output "BACKUP_RESTORE_PASSED=$output"
}finally{
    foreach($name in $containerNames){[void](Invoke-Program docker @('stop',$name) -AllowFailure)}
    [void](Lab-Compose $lab @('start','app-a','app-b'))
}
