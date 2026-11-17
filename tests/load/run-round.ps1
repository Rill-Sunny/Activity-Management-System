param([Parameter(Mandatory)][string]$RunDirectory,[Parameter(Mandatory)][string]$Phase,
    [ValidateSet('S01','S02','S03','S04','S05','S06','S07','S08','S09')][string]$Scenario,
    [int]$Vus=50,[switch]$Cold,[switch]$LastSlot,[switch]$Smoke)
. (Join-Path $PSScriptRoot '../system/common.ps1')
$lab=Open-Lab $RunDirectory
Assert-Lab ($Phase -match '^[a-z0-9-]+$') 'Invalid phase'
$label="$Scenario-vu$Vus"+$(if($Cold){'-cold'}else{''})+$(if($LastSlot){'-last-slot'}else{''})
$round=Join-Path $lab.directory ($Phase+'/'+$label+'-'+(Get-Date -Format HHmmss))
[void][IO.Directory]::CreateDirectory($round)
Write-Output "PREPARING $round"
& (Join-Path $PSScriptRoot 'reset/reset.ps1') -RunDirectory $RunDirectory
& (Join-Path $PSScriptRoot 'fixtures/prepare.ps1') -RunDirectory $RunDirectory -OutputDirectory $round -Scenario $Scenario -Vus $Vus -LastSlot:$LastSlot
[void](Lab-Sql $lab 'TRUNCATE TABLE performance_schema.events_statements_summary_by_digest; TRUNCATE TABLE mysql.slow_log;' -Root)
$scripts=@{S01='free-reservation';S02='ticket-payment';S03='seat-contention';S04='purchase-limit';S05='multi-sku-payment';S06='product-limit';S07='duplicate-operations';S08='cache-read';S09='cancellation-async'}
$before=Lab-Sql $lab "SHOW GLOBAL STATUS WHERE Variable_name IN ('Innodb_row_lock_waits','Innodb_row_lock_time','Threads_created','Questions');" -Root
$sourceFiles=@(Get-ChildItem (Join-Path $PSScriptRoot '.') -Recurse -File|ForEach-Object{@{path=$_.FullName.Substring($RepoRoot.Length+1);hash=(Get-FileHash $_.FullName).Hash}})
Save-Lab (Join-Path $round 'workload-source.json') $sourceFiles
$sampler=Start-Process (Get-Process -Id $PID).Path -ArgumentList @('-NoProfile','-File',('"'+(Join-Path $PSScriptRoot 'collect.ps1')+'"'),'-RunDirectory',('"'+$RunDirectory+'"'),'-OutputDirectory',('"'+$round+'"')) -WindowStyle Hidden -PassThru
$started=[DateTimeOffset]::UtcNow
$args=@('run','--rm','--pull','never','--network',($lab.context.project+'_backend'),'-v',($RepoRoot+':/work:ro'),'-v',($round+':/results'),'-e','FIXTURE_FILE=/results/fixture.private.json','-e',"VUS=$Vus",'-e',"SCENARIO=$Scenario",'-e',('RUN_ID='+$label))
if($Cold){$args+=@('-e','MODE=cold')};if($Smoke){$args+=@('-e','WARMUP=5s','-e','DURATION=10s')}
$args+=@('grafana/k6:1.8.1','run',('/work/tests/load/scenarios/'+$scripts[$Scenario]+'.js'))
Write-Output "RUNNING $label ($Phase)"
try{
    $result=Invoke-Program docker $args -AllowFailure -OutputFile (Join-Path $round 'k6-output.log')
    $result.Error|Set-Content (Join-Path $round 'k6-error.log') -Encoding utf8
    $httpFinished=[DateTimeOffset]::UtcNow
    if($Scenario -eq 'S09'){Wait-Lab 'cancellation final convergence' {(Lab-Sql $lab "SELECT COUNT(*) FROM cancellation_batch WHERE status='COMPLETED';") -eq '1'} 240}
    Wait-Lab 'outbox publication convergence' {(Lab-Sql $lab "SELECT COUNT(*) FROM outbox_event WHERE status<>'SENT';") -eq '0'} 120
    $assertionFailure=$null
    try{$business=& (Join-Path $PSScriptRoot 'assert-round.ps1') -RunDirectory $RunDirectory -OutputDirectory $round}
    catch{
        $assertionFailure=$_.Exception.Message
        $business=Get-Content (Join-Path $round 'business-result.json') -Raw|ConvertFrom-Json
        (Lab-Compose $lab @('logs','--no-color','--since','5m','app-a','app-b'))|Set-Content (Join-Path $round 'failure-app.log')
        Save-Lab (Join-Path $round 'assertion-failure.json') @{passed=$false;message=$assertionFailure}
    }
    $finished=[DateTimeOffset]::UtcNow
    $summary=Get-Content (Join-Path $round 'k6-summary.json') -Raw|ConvertFrom-Json
    $duration=if($Scenario -eq 'S08' -and -not $Cold){if($Smoke){10}else{300}}else{$summary.state.testRunDurationMs/1000}
    $newEffects=switch($Scenario){'S01'{$business.reservations};'S02'{$business.paidOrders};'S03'{$business.paidOrders};'S04'{$business.paidOrders};'S05'{0};'S06'{$business.productPaid};'S07'{2};'S08'{0};'S09'{$business.refunds}}
    $after=Lab-Sql $lab "SHOW GLOBAL STATUS WHERE Variable_name IN ('Innodb_row_lock_waits','Innodb_row_lock_time','Threads_created','Questions'); SELECT DIGEST_TEXT,COUNT_STAR,SUM_TIMER_WAIT/1000000000000 seconds_total,AVG_TIMER_WAIT/1000000000 avg_ms,SUM_LOCK_TIME/1000000000000 lock_seconds,SUM_ROWS_EXAMINED FROM performance_schema.events_statements_summary_by_digest WHERE SCHEMA_NAME=DATABASE() ORDER BY SUM_TIMER_WAIT DESC LIMIT 25; SELECT start_time,query_time,lock_time,rows_sent,rows_examined,CONVERT(sql_text USING utf8mb4) FROM mysql.slow_log WHERE db=DATABASE() ORDER BY query_time DESC LIMIT 30;" -Root
    $after|Set-Content (Join-Path $round 'mysql-after.txt') -Encoding utf8
    $before|Set-Content (Join-Path $round 'mysql-before.txt') -Encoding utf8
    $auditFile=Join-Path $round 'after/reconciliation.json'
    $auditDifferences=if(Test-Path $auditFile){(Get-Content $auditFile -Raw|ConvertFrom-Json|Where-Object {-not $_.diagnostic}|Measure-Object differences -Sum).Sum}else{$null}
    Save-Lab (Join-Path $round 'result.json') @{phase=$Phase;scenario=$Scenario;vus=$Vus;cold=[bool]$Cold;smoke=[bool]$Smoke;lastSlot=[bool]$LastSlot;startedAt=$started.ToString('o');httpFinishedAt=$httpFinished.ToString('o');finishedAt=$finished.ToString('o');durationSeconds=$duration;convergenceSeconds=($finished-$started).TotalSeconds;exitCode=$result.ExitCode;requests=$summary.metrics.measured_requests.values.count;rps=$summary.metrics.measured_requests.values.count/$duration;newBusinessEffects=$newEffects;effectiveTps=$newEffects/$(if($Scenario -eq 'S09'){($finished-$started).TotalSeconds}else{$duration});latency=$summary.metrics.measured_latency.values;successLatency=$summary.metrics.successful_latency.values;technicalErrors=$summary.metrics.technical_errors.values;businessChecks=$summary.metrics.business_checks.values;accepted=$summary.metrics.accepted_responses.values.count;expectedRefusals=$summary.metrics.expected_refusals.values.count;assertionFailure=$assertionFailure;databaseDifferences=$auditDifferences;sourceCommit=$lab.context.sourceCommit;imageId=(Invoke-Program docker @('image','inspect',$lab.context.image,'--format','{{.Id}}')).Output.Trim()}
    Assert-Lab (-not $assertionFailure) "Business assertion failed: $assertionFailure; inspect $round"
    Assert-Lab ($result.ExitCode -eq 0) "k6 failed ($($result.ExitCode)); inspect $round"
    Write-Output "PASSED $round"
}finally{
    [IO.File]::WriteAllText((Join-Path $round 'sampling.stop'),'stop')
    if(-not $sampler.WaitForExit(15000)){$sampler.Kill()}
    $sampler.Dispose()
}
