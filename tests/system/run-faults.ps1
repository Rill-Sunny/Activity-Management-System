param([Parameter(Mandatory)][string]$RunDirectory,[Parameter(Mandatory)][ValidateSet('Redis','Rabbit','F01','F02','F06','F07')][string]$Scenario)
. (Join-Path $PSScriptRoot 'common.ps1')
$lab=Open-Lab $RunDirectory;Assert-Lab ($lab.context.day -eq 28) 'Fault experiments require Day 28 isolation'
$output=Join-Path $lab.directory ($Scenario+'-'+(Get-Date -Format 'HHmmss'));[void][IO.Directory]::CreateDirectory($output)
& (Join-Path $RepoRoot 'tests/load/reset/reset.ps1') -RunDirectory $RunDirectory
& (Join-Path $RepoRoot 'tests/load/fixtures/prepare.ps1') -RunDirectory $RunDirectory -OutputDirectory $output -Scenario FAULT
$f=Get-Content (Join-Path $output 'fixture.private.json') -Raw|ConvertFrom-Json
$t=$f.tokens[0];$timeline=[Collections.Generic.List[object]]::new()
function Mark([string]$step,$details=$null){$timeline.Add(@{at=[DateTimeOffset]::Now.ToString('o');step=$step;details=$details});Save-Lab (Join-Path $output 'timeline.json') @($timeline);Write-Output "$Scenario $step"}
function CreateTicket([string]$key,[string]$token=$t){Lab-Post $lab '/ticket-orders' $token @{requestId=$key;sessionId=$f.sessionId;items=@(@{zoneId=$f.zoneId;allocationMode='AUTO_ASSIGN';quantity=1;acceptNonAdjacent=$true})}}
function PayTicket($order,[string]$token=$t){Lab-Post $lab "/ticket-orders/$($order.orderId)/pay" $token}
function Ready {Wait-Lab 'applications ready' {try{(Invoke-RestMethod ($lab.appA+'/actuator/health')).status -eq 'UP' -and (Invoke-RestMethod ($lab.appB+'/actuator/health')).status -eq 'UP'}catch{$false}} 180}
function Converge {Wait-Lab 'outbox and notifications' {(Lab-Sql $lab "SELECT (SELECT COUNT(*) FROM outbox_event WHERE status<>'SENT')+(SELECT COUNT(*) FROM outbox_event o LEFT JOIN consumer_process p ON p.event_id=o.event_id WHERE o.event_type IN ('PAYMENT_SUCCEEDED','REFUND_SUCCEEDED') AND p.event_id IS NULL);") -eq '0'} 180}
function RabbitStart {[void](Lab-Compose $lab @('start','rabbitmq'));Wait-Lab 'Rabbit ready' {try{[void](Invoke-RestMethod ($lab.broker+'/api/overview') -Headers $mqHeaders -TimeoutSec 3);$true}catch{$false}} 90}
$mqHeaders=@{Authorization='Basic '+[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($lab.settings.RABBITMQ_USERNAME+':'+$lab.settings.RABBITMQ_PASSWORD))}
function PublishEvent([string]$event,[string]$route){
    $payload=Lab-Sql $lab "SELECT payload FROM outbox_event WHERE event_id='$event';"
    $body=@{properties=@{delivery_mode=2;message_id=$event;headers=@{'x-event-id'=$event}};routing_key=$route;payload=$payload;payload_encoding='string'}|ConvertTo-Json -Depth 12
    $result=Invoke-RestMethod ($lab.broker+'/api/exchanges/%2F/las.events.exchange/publish') -Headers $mqHeaders -Method Post -ContentType application/json -Body $body
    Assert-Lab $result.routed 'Replay did not route'
}
function QueueStats {Invoke-RestMethod ($lab.broker+'/api/queues/%2F/las.notification.payment') -Headers $mqHeaders}
$sampler=Start-Process (Get-Process -Id $PID).Path -ArgumentList @('-NoProfile','-File',('"'+(Join-Path $RepoRoot 'tests/load/collect.ps1')+'"'),'-RunDirectory',('"'+$lab.directory+'"'),'-OutputDirectory',('"'+$output+'"')) -WindowStyle Hidden -PassThru
try{
    Mark 'baseline checked'
    if(-not (Test-Path (Join-Path $lab.directory 'before-faults.sql'))){
        & (Join-Path $DeployRoot 'scripts/backup-db.ps1') -Project $lab.context.project -EnvFile $lab.context.envFile -OutputFile (Join-Path $lab.directory 'before-faults.sql')
        Mark 'initial consistent backup saved'
    }
    if($Scenario -eq 'Redis'){
        [void](Lab-Api $lab "/activities/$($f.activityId)" -method GET);[void](Lab-Api $lab "/products/$($f.productId)" -method GET)
        $order=Lab-Post $lab '/product-orders/confirm' $t @{requestId='redis-payment';items=@(@{skuId=$f.skuA;quantity=2})}
        [void](Lab-Compose $lab @('stop','--timeout','10','redis'));Mark 'Redis stopped'
        $args=@('run','--rm','--pull','never','--network',($lab.context.project+'_backend'),'-v',($RepoRoot+':/work:ro'),'-v',($output+':/results'),'grafana/k6:1.8.1','run','/work/tests/system/redis-down.js')
        $load=Invoke-Program docker $args -AllowFailure -OutputFile (Join-Path $output 'redis-load.log');$load.Error|Set-Content (Join-Path $output 'redis-load-error.log')
        Assert-Lab ($load.ExitCode -eq 0) 'Redis-down concurrency had uncontrolled results'
        $paid=Lab-Post $lab "/product-orders/$($order.orderId)/pay" $t
        [void](Lab-Post $lab "/product-orders/$($order.orderId)/pay" $t -B)
        $item=[long](Lab-Sql $lab "SELECT order_item_id FROM product_order_item WHERE order_id=$($order.orderId);")
        $body=@{requestId='redis-refund';orderId=$order.orderId;items=@(@{orderItemId=$item;quantity=1;reason='QUALITY_PROBLEM'})}
        $refund=Lab-Post $lab '/product-after-sales' $t $body
        $duplicate=Lab-Post $lab '/product-after-sales' $t $body -B
        Assert-Lab ($null -ne $refund.afterSaleRequestId -and $refund.afterSaleRequestId -eq $duplicate.afterSaleRequestId) 'Refund identity changed'
        Assert-Lab ((Lab-Sql $lab "SELECT remains_stock FROM sku WHERE sku_id=$($f.skuA);") -eq '999') 'Redis outage duplicated stock effects'
        Assert-Lab ((Lab-Sql $lab "SELECT valid_count FROM user_product_purchase WHERE product_id=$($f.productId);") -eq '1') 'Redis outage duplicated quota effects'
        [void](Lab-Post $lab "/publisher/products/$($f.productId)/off-sale" $f.publisherToken)
        $pending=[int](Lab-Sql $lab 'SELECT COUNT(*) FROM cache_invalidation_task WHERE completed_at IS NULL;')
        Assert-Lab ($pending -gt 0) 'Missing durable invalidation backlog';Mark 'trades committed during outage' @{pendingInvalidations=$pending;stock=999;validCount=1}
        $recovery=[DateTimeOffset]::Now;[void](Lab-Compose $lab @('start','redis'))
        Wait-Lab 'cache invalidation backlog drained' {(Lab-Sql $lab 'SELECT COUNT(*) FROM cache_invalidation_task WHERE completed_at IS NULL;') -eq '0'} 120
        $generation=Lab-Sql $lab "SELECT generation FROM cache_generation WHERE cache_identity='las:product:$($f.productId)';"
        $redisGeneration=(Lab-Compose $lab @('exec','-T','redis','redis-cli','GET',"las:cache-generation:las:product:$($f.productId)")).Trim()
        Assert-Lab ($generation -eq $redisGeneration) 'Redis generation differs from MySQL'
        Assert-Lab ((Lab-Api $lab "/products/$($f.productId)" -method GET).status -eq 404) 'Off-sale Product cache did not converge'
        Mark 'Redis recovered and cache converged' @{seconds=([DateTimeOffset]::Now-$recovery).TotalSeconds;mysqlGeneration=$generation;redisGeneration=$redisGeneration}
    }elseif($Scenario -eq 'Rabbit'){
        [void](Lab-Compose $lab @('stop','--timeout','10','rabbitmq'));Mark 'RabbitMQ stopped'
        $order=CreateTicket 'broker-outage';$paid=PayTicket $order
        $event=Lab-Sql $lab "SELECT event_id FROM outbox_event WHERE event_type='PAYMENT_SUCCEEDED' AND aggregate_id=$($order.orderId);"
        Wait-Lab 'publish failure recorded' {(Lab-Sql $lab "SELECT COUNT(*) FROM outbox_event WHERE event_id='$event' AND status='PENDING' AND attempts>0 AND failure_reason IS NOT NULL;") -eq '1'} 45
        Mark 'business committed, Outbox retained' @{orderId=$order.orderId;eventId=$event;outbox=(Lab-Sql $lab "SELECT status,attempts,failure_reason FROM outbox_event WHERE event_id='$event';")}
        $recovery=[DateTimeOffset]::Now;RabbitStart;Converge
        Mark 'broker recovery delivered committed effect' @{seconds=([DateTimeOffset]::Now-$recovery).TotalSeconds}
        Wait-Lab 'original notification acknowledged' {[long](QueueStats).message_stats.ack -ge 1} 30
        $ackedBefore=[long](QueueStats).message_stats.ack
        PublishEvent $event 'notification.payment.success';PublishEvent $event 'notification.payment.success'
        Wait-Lab 'both duplicate deliveries acknowledged' { [long](QueueStats).message_stats.ack -ge ($ackedBefore+2) } 30
        $expiry=Lab-Sql $lab "SELECT event_id FROM outbox_event WHERE event_type='ORDER_EXPIRED' AND aggregate_id=$($order.orderId);"
        PublishEvent $expiry 'order.expired.execute'
        Wait-Lab 'expiry delivery processed' {(Lab-Sql $lab "SELECT COUNT(*) FROM consumer_process WHERE event_id='$expiry';") -eq '1'} 30
        Assert-Lab ((Lab-Sql $lab "SELECT status FROM ticket_order WHERE order_id=$($order.orderId);") -eq 'PAID') 'Old expiry closed a paid order'
        Assert-Lab ((Lab-Sql $lab "SELECT COUNT(*) FROM notification WHERE event_id='$event';") -eq '1') 'Duplicate notification effect'
        Mark 'duplicate and old expiry messages safe' @{eventId=$event;effectCount=1}
    }elseif($Scenario -eq 'F06'){
        [void](Lab-Compose $lab @('stop','--timeout','10','rabbitmq'))
        $order=CreateTicket 'poison-message';[void](PayTicket $order)
        $event=Lab-Sql $lab "SELECT event_id FROM outbox_event WHERE event_type='PAYMENT_SUCCEEDED' AND aggregate_id=$($order.orderId);"
        # Stop publishers before corrupting the owned event; otherwise an in-flight relay
        # could retain the original payload and turn this experiment into a timing race.
        [void](Lab-Compose $lab @('stop','app-a','app-b'))
        $changed=Lab-Sql $lab "UPDATE outbox_event SET payload=JSON_SET(payload,'$.userId',0) WHERE event_id='$event' AND status IN ('PENDING','CLAIMED'); SELECT ROW_COUNT();"
        Assert-Lab ($changed -eq '1') 'Poison payload was not injected into exactly one unpublished event'
        Mark 'poison payload injected into owned event' @{eventId=$event;orderId=$order.orderId}
        RabbitStart
        [void](Lab-Compose $lab @('start','app-a','app-b'));Ready
        Wait-Lab 'bounded retries reach terminal failure' {(Lab-Sql $lab "SELECT COUNT(*) FROM consumer_failure WHERE event_id='$event' AND status='FAILED' AND attempts=3;") -eq '1'} 90
        Wait-Lab 'dead letter visible' {(Invoke-RestMethod ($lab.broker+'/api/queues/%2F/las.notification.payment.dead') -Headers $mqHeaders).messages -eq 1} 30
        Assert-Lab ((Lab-Sql $lab "SELECT COUNT(*) FROM consumer_process WHERE event_id='$event';") -eq '0') 'Poison message committed an effect'
        Assert-Lab ((Lab-Sql $lab "SELECT status FROM ticket_order WHERE order_id=$($order.orderId);") -eq 'PAID') 'Consumer failure rolled back committed payment'
        Lab-Reconcile $lab (Join-Path $output 'terminal-failure')
        $failure=Lab-Sql $lab "SELECT event_id,consumer_name,status,attempts,last_error FROM consumer_failure WHERE event_id='$event';"
        Mark 'poison message retained in queryable DLQ' @{record=$failure}
        # Repair only this deliberately corrupted payload; preserve the original durable event ID.
        [void](Lab-Sql $lab "UPDATE outbox_event SET payload=JSON_SET(payload,'$.userId',271001) WHERE event_id='$event';")
        [void](Lab-Post $lab "/admin/outbox-events/$event/replay" $f.adminToken)
        Converge
        Assert-Lab ((Lab-Sql $lab "SELECT COUNT(*) FROM notification WHERE event_id='$event';") -eq '1') 'Replay did not create exactly one notification'
        Assert-Lab ((Lab-Api $lab "/admin/outbox-events/$event/replay" $f.adminToken).status -eq 409) 'Committed effect allowed another admin replay'
        $dead=Invoke-RestMethod ($lab.broker+'/api/queues/%2F/las.notification.payment.dead/get') -Headers $mqHeaders -Method Post -ContentType application/json -Body '{"count":1,"ackmode":"ack_requeue_false","encoding":"auto","truncate":10000}'
        Save-Lab (Join-Path $output 'resolved-dead-letter.json') $dead
        Assert-Lab ($dead[0].properties.message_id -eq $event) 'Unexpected dead letter identity'
        Mark 'same event repaired and replayed once' @{eventId=$event;effectCount=1;archivedResolvedDeadLetter=$true}
    }elseif($Scenario -in @('F01','F02')){
        [void](Lab-Compose $lab @('stop','--timeout','10','rabbitmq'))
        $order=CreateTicket "crash-$Scenario";$paid=PayTicket $order
        $event=Lab-Sql $lab "SELECT event_id FROM outbox_event WHERE event_type='PAYMENT_SUCCEEDED' AND aggregate_id=$($order.orderId);"
        [void](Lab-Compose $lab @('stop','app-a','app-b'));RabbitStart
        # Make the original failed publication immediately due without altering its event identity or attempts.
        [void](Lab-Sql $lab "UPDATE outbox_event SET next_attempt_at=NOW(3) WHERE status='PENDING';")
        $brokerBeforeCrash=QueueStats
        $ackedBeforeCrash=[long]$brokerBeforeCrash.message_stats.ack
        $redeliveredBeforeCrash=[long]$brokerBeforeCrash.message_stats.redeliver
        & (Join-Path $PSScriptRoot 'run-crash-probe.ps1') -RunDirectory $RunDirectory -Mode $Scenario -EventId $event -OutputDirectory $output
        Mark 'worker process halted at boundary' (Get-Content (Join-Path $output 'crash-boundary.json') -Raw|ConvertFrom-Json)
        Wait-Lab 'broker retains work after process exit' {[long](QueueStats).messages_ready -ge 1} 30
        Save-Lab (Join-Path $output 'broker-after-crash.json') (QueueStats)
        if($Scenario -eq 'F01'){Assert-Lab ((Lab-Sql $lab "SELECT status FROM outbox_event WHERE event_id='$event';") -eq 'CLAIMED') 'Relay incorrectly marked SENT before its crash boundary'}
        $recovery=[DateTimeOffset]::Now;[void](Lab-Compose $lab @('start','app-a','app-b'));Ready;Converge
        Assert-Lab ((Lab-Sql $lab "SELECT COUNT(*) FROM notification WHERE event_id='$event';") -eq '1') 'Crash recovery duplicated notification'
        Assert-Lab ((Lab-Sql $lab "SELECT COUNT(*) FROM consumer_process WHERE event_id='$event' AND consumer_name='payment-notification-v1';") -eq '1') 'Crash recovery duplicated process marker'
        if($Scenario -eq 'F01'){Assert-Lab ([int](Lab-Sql $lab "SELECT attempts FROM outbox_event WHERE event_id='$event';") -ge 2) 'Relay did not reclaim its expired lease'}
        if($Scenario -eq 'F01'){Wait-Lab 'both confirmed publications consumed' {[long](QueueStats).message_stats.ack -ge ($ackedBeforeCrash+2) -and [long](QueueStats).messages -eq 0} 30}
        if($Scenario -eq 'F02'){Wait-Lab 'redelivery acknowledged and queue drained' {
            $queue=QueueStats
            [long]$queue.message_stats.redeliver -gt $redeliveredBeforeCrash -and [long]$queue.message_stats.ack -ge ($ackedBeforeCrash+1) -and [long]$queue.messages -eq 0
        } 30}
        Save-Lab (Join-Path $output 'broker-delivery-evidence.json') (QueueStats)
        Mark 'process recovery converged' @{seconds=([DateTimeOffset]::Now-$recovery).TotalSeconds;eventId=$event;effectCount=1;outbox=(Lab-Sql $lab "SELECT status,attempts FROM outbox_event WHERE event_id='$event';")}
    }else{
        foreach($i in 0..2){$order=CreateTicket "takeover-$i" $f.tokens[$i];[void](PayTicket $order $f.tokens[$i])};Converge
        [void](Lab-Compose $lab @('stop','app-a','app-b'))
        & (Join-Path $PSScriptRoot 'run-crash-probe.ps1') -RunDirectory $RunDirectory -Mode F07 -OutputDirectory $output -PublisherToken $f.publisherToken
        $boundary=Get-Content (Join-Path $output 'crash-boundary.json') -Raw|ConvertFrom-Json
        Assert-Lab ((Lab-Sql $lab "SELECT status FROM cancellation_batch_item WHERE item_id=$($boundary.itemId);") -eq 'PROCESSING') 'Claim did not commit before crash'
        Mark 'cancellation process halted after durable claim' $boundary
        $recovery=[DateTimeOffset]::Now;[void](Lab-Compose $lab @('start','app-a','app-b'));Ready
        Wait-Lab 'cancellation reclaimed and completed' {(Lab-Sql $lab "SELECT COUNT(*) FROM cancellation_batch WHERE status='COMPLETED';") -eq '1'} 180;Converge
        Assert-Lab ([int](Lab-Sql $lab "SELECT attempt_count FROM cancellation_batch_item WHERE item_id=$($boundary.itemId);") -ge 2) 'Task lease was not reclaimed'
        Assert-Lab ((Lab-Sql $lab "SELECT COUNT(*) FROM ticket_refund_request WHERE status='SUCCEEDED';") -eq '3') 'Cancellation takeover duplicated refunds'
        $rejected=Lab-Api $lab '/ticket-orders' $t @{requestId='cancelled-resale';sessionId=$f.sessionId;items=@(@{zoneId=$f.zoneId;allocationMode='AUTO_ASSIGN';quantity=1;acceptNonAdjacent=$true})}
        Assert-Lab ($rejected.status -eq 409) 'Cancelled session accepted new sales'
        Mark 'lease takeover converged without reselling' @{seconds=([DateTimeOffset]::Now-$recovery).TotalSeconds;refunds=3;itemId=$boundary.itemId;resaleStatus=$rejected.status}
    }
    Ready;Lab-Reconcile $lab (Join-Path $output 'after')
    (Lab-Sql $lab "SELECT event_id,event_type,status,attempts,traceparent FROM outbox_event ORDER BY created_at,event_id; SELECT consumer_name,event_id,processed_at FROM consumer_process ORDER BY consumer_name,event_id; SELECT notification_id,event_id,user_id FROM notification ORDER BY notification_id; SELECT item_id,target_id,status,attempt_count,refund_id FROM cancellation_batch_item ORDER BY item_id;")|Set-Content (Join-Path $output 'event-reconciliation.txt')
    Mark 'all fourteen SQL audits passed'
    Save-Lab (Join-Path $output 'result.json') @{scenario=$Scenario;passed=$true;sourceCommit=$lab.context.sourceCommit;databaseDifferences=0;completedAt=[DateTimeOffset]::Now.ToString('o')}
}catch{
    Save-Lab (Join-Path $output 'failure.json') @{passed=$false;scenario=$Scenario;at=[DateTimeOffset]::Now.ToString('o');error=$_.Exception.Message;position=$_.InvocationInfo.PositionMessage}
    throw
}finally{
    [void](Lab-Compose $lab @('start','redis','rabbitmq','app-a','app-b'))
    (Lab-Compose $lab @('logs','--no-color','--since','20m','app-a','app-b'))|Set-Content (Join-Path $output 'application.log')
    [IO.File]::WriteAllText((Join-Path $output 'sampling.stop'),'stop');if(-not $sampler.WaitForExit(15000)){$sampler.Kill()};$sampler.Dispose()
}
