$state=Get-Content (Join-Path $evidence 'state.private.json') -Raw | ConvertFrom-Json
$events=[Collections.Generic.List[object]]::new()
function Stamp([string]$experiment,[string]$step,$detail=$null) {
    $events.Add(@{experiment=$experiment;step=$step;at=[DateTimeOffset]::Now.ToString('o');detail=$detail})
    Save 'fault-timeline.json' $events
}
function Alerts { (Invoke-RestMethod ($prom+'/api/v1/alerts')).data.alerts }
function Logs([string]$label) {
    foreach($name in @('app-a','app-b')){(Compose @('logs','--no-color','--no-log-prefix',$name)) | Set-Content -LiteralPath (Join-Path $evidence "$label-$name.log") -Encoding utf8}
}

# A real unresolved event ages naturally. Only its next attempt is deferred; created_at is not backdated.
$poisonId=[Guid]::NewGuid().ToString();$poisonTrace=[Guid]::NewGuid().ToString('N')
$payload=@{eventId=$poisonId;orderId=$state.paidOrderId;userId=$state.accounts.buyer.id;content=''} | ConvertTo-Json -Compress
$hex=[Convert]::ToHexString([Text.Encoding]::UTF8.GetBytes($payload))
[void](Sql "INSERT INTO outbox_event(event_id,event_type,aggregate_id,payload,status,traceparent,next_attempt_at) VALUES('$poisonId','PAYMENT_SUCCEEDED',$($state.paidOrderId),CAST(CONVERT(0x$hex USING utf8mb4) AS JSON),'PENDING','00-$poisonTrace-1234567890abcdef-01',NOW(3)+INTERVAL 10 MINUTE);")
Stamp 'outbox' 'deferred-real-event' @{eventId=$poisonId;fixture='next attempt deferred; current created_at'}
try {
    Stamp 'instance' 'stop-app-a'
    [void](Compose @('stop','-t','10','app-a'))
    Wait-For 'individual target DOWN while B UP' { (Number 'up{job="las-backend",instance="app-a"}') -eq 0 -and (Number 'up{job="las-backend",instance="app-b"}') -eq 1 } 60
    Save 'targets-during-stop.json' (Invoke-RestMethod ($prom+'/api/v1/targets'))
    Wait-For 'instance alert firing' { (Number 'ALERTS{alertname="LasInstanceDown",alertstate="firing",instance="app-a"}') -eq 1 } 130
    Stamp 'instance' 'firing' (Alerts)
    Check (Ready $appB) 'Healthy instance B was affected by stopping A'
    Stamp 'instance' 'diagnosed' 'app-a target DOWN; app-b target and readiness UP'
    Wait-For 'Outbox age alert firing' { (Number 'ALERTS{alertname="LasOutboxOldestAge",alertstate="firing"}') -eq 1 } 100
    Stamp 'outbox' 'firing' @{pending=Query 'outbox_pending';oldestAge=Query 'outbox_oldest_age_seconds';alerts=Alerts}
} finally {
    [void](Compose @('start','app-a'))
    Stamp 'instance' 'restarted-app-a'
    [void](Sql "UPDATE outbox_event SET next_attempt_at=NOW(3) WHERE event_id='$poisonId' AND status='PENDING';")
    Stamp 'outbox' 'released-for-publish'
}
Wait-For 'app-a recovery' {Ready $appA} 180
Wait-For 'instance alert resolved' { (Number 'up{job="las-backend",instance="app-a"}') -eq 1 -and (Number 'ALERTS{alertname="LasInstanceDown",alertstate="firing"}') -eq 0 } 90
Stamp 'instance' 'resolved' (Alerts)
Wait-For 'Outbox alert resolved' { (Number 'max(outbox_pending)') -eq 0 -and (Number 'ALERTS{alertname="LasOutboxOldestAge",alertstate="firing"}') -eq 0 } 90
Stamp 'outbox' 'resolved' @{status=(Sql "SELECT status FROM outbox_event WHERE event_id='$poisonId';");alerts=Alerts}

Wait-For 'poison reaches terminal consumer failure' { (Sql "SELECT COUNT(*) FROM consumer_failure WHERE event_id='$poisonId' AND attempts=3 AND status='FAILED';") -eq '1' } 90
Check ((Sql "SELECT COUNT(*) FROM notification WHERE event_id='$poisonId';") -eq '0') 'Poison created a business effect'
Check ((Sql "SELECT COUNT(*) FROM consumer_process WHERE event_id='$poisonId';") -eq '0') 'Poison committed a success marker'
Wait-For 'DLQ alert firing' { (Number 'ALERTS{alertname="LasDeadLetterMessages",alertstate="firing"}') -eq 1 } 90
Stamp 'consumer' 'firing' @{dlq=Query 'rabbitmq_queue_messages{queue=~".*[.]dead"}';outcomes=Query 'mq_consume_seconds_count{result=~"RETRY|DLQ"}';attempts=3;eventId=$poisonId;alerts=Alerts}
Check ((Number 'sum(mq_consume_seconds_count{result="RETRY"})') -ge 2) 'Confirmed consumer retries were not measured'
$poisonSpans=Read-Trace $poisonTrace @('outbox.publish','mq.consume')
Save 'poison-trace.json' @{traceId=$poisonTrace;eventId=$poisonId;spans=$poisonSpans}
Stamp 'consumer' 'diagnosed' @{cause='IllegalArgumentException: empty notification content';transaction='rolled back';traceId=$poisonTrace}
[void](Sql "UPDATE outbox_event SET payload=JSON_SET(payload,'$.content','Day25 repaired notification') WHERE event_id='$poisonId';")
[void](Post $appB "/admin/outbox-events/$poisonId/replay" $state.accounts.admin.token)
Wait-For 'replay committed exactly once' { (Sql "SELECT COUNT(*) FROM consumer_process WHERE consumer_name='payment-notification-v1' AND event_id='$poisonId';") -eq '1' }
Check ((Sql "SELECT COUNT(*) FROM notification WHERE event_id='$poisonId';") -eq '1') 'Replay did not produce exactly one effect'
$dead=Broker POST '/api/queues/%2F/las.notification.payment.dead/get' @{count=1;ackmode='ack_requeue_false';encoding='auto';truncate=5000}
Check (@($dead).Count -eq 1 -and $dead[0].properties.headers.'x-event-id' -eq $poisonId) 'Unexpected DLQ message identity'
Save 'dead-message-retired-after-replay.json' $dead
Stamp 'consumer' 'recovered' 'Payload repaired; admin replay committed; original DLQ copy acknowledged'
Wait-For 'DLQ alert resolved' { (Number 'sum(rabbitmq_queue_messages{queue=~".*[.]dead"})') -eq 0 -and (Number 'ALERTS{alertname="LasDeadLetterMessages",alertstate="firing"}') -eq 0 } 90
Stamp 'consumer' 'resolved' (Alerts)
Logs 'before-redis-fault'

# Break only app-a's Redis connection. The Redis container and app-b keep running.
$override=Join-Path $evidence 'redis-fault.yaml'
@'
services:
  app-a:
    environment:
      REDIS_PORT: "1"
'@ | Set-Content -LiteralPath $override -Encoding utf8
$faultCompose=$compose+@('-f',$override)
$before=Sql "SELECT CONCAT((SELECT remains_stock FROM sku WHERE sku_id=250003),':',(SELECT valid_count FROM user_product_purchase WHERE user_id=$($state.accounts.buyer.id) AND product_id=250003),':',(SELECT remains_amount FROM session_zone WHERE zone_id=250001),':',(SELECT COUNT(*) FROM ticket_refund_item WHERE refund_id=$($state.refundedId) AND status='SUCCEEDED'));"
$oldStart=Number 'process_start_time_seconds{job="las-backend",instance="app-a"}'
try {
    Stamp 'redis' 'injected-app-a-port-1'
    [void](Invoke-Program docker ($faultCompose+@('up','-d','--no-deps','--force-recreate','app-a','--pull','never')))
    Wait-For 'restarted app-a metrics available' {try{(Number 'process_start_time_seconds{job="las-backend",instance="app-a"}') -gt $oldStart}catch{$false}} 180
    $latencies=@()
    foreach($round in 1..3){
        foreach($path in @('/activities/250001','/products/250002')){
            $watch=[Diagnostics.Stopwatch]::StartNew();$r=Api $appA $path '' $null 'GET';$watch.Stop()
            Check ($r.status -eq 200) "Cache fallback did not return a controlled successful read: $path"
            $latencies+=@{path=$path;status=$r.status;milliseconds=$watch.ElapsedMilliseconds}
        }
    }
    [void](Post $appA "/ticket-orders/$($state.paidOrderId)/pay" $state.accounts.buyer.token)
    [void](Post $appA "/product-orders/$($state.paidProductOrderId)/pay" $state.accounts.buyer.token)
    [void](Post $appA "/ticket-refunds/$($state.refundedId)/process" $state.accounts.buyer.token)
    $after=Sql "SELECT CONCAT((SELECT remains_stock FROM sku WHERE sku_id=250003),':',(SELECT valid_count FROM user_product_purchase WHERE user_id=$($state.accounts.buyer.id) AND product_id=250003),':',(SELECT remains_amount FROM session_zone WHERE zone_id=250001),':',(SELECT COUNT(*) FROM ticket_refund_item WHERE refund_id=$($state.refundedId) AND status='SUCCEEDED'));"
    Check ($after -eq $before) 'Redis failure changed stock/quota/refund invariants during repeated requests'
    Wait-For 'fallback metric scraped' { (Number 'sum(cache_db_fallback_total{instance="app-a"})') -ge 6 } 60
    $health=Api $appA '/actuator/health' '' $null 'GET'
    Check ($health.status -eq 503) 'Redis failure must be visible in readiness'
    Save 'redis-fault.json' @{latencies=$latencies;fallbacks=Query 'cache_db_fallback_total{instance="app-a"}';readiness=$health;before=$before;after=$after;appBReady=(Ready $appB);redisContainer=(Compose @('ps','--status','running','--services','redis')).Trim()}
    Stamp 'redis' 'diagnosed' 'app-a connection refused, cache MySQL fallback observed, readiness 503, repeated transaction invariants unchanged'
    Logs 'during-redis-fault'
} finally {
    [void](Compose @('up','-d','--no-deps','--force-recreate','app-a','--pull','never'))
    Stamp 'redis' 'connection-restored'
}
Wait-For 'app-a ready after Redis configuration restored' {Ready $appA} 180
foreach($round in 1..2){[void](Api $appA '/activities/250001' '' $null 'GET')}
Wait-For 'Redis hit metric recovered' {(Number 'sum(cache_hit_total{instance="app-a",cache="activity"})') -gt 0} 60
Stamp 'redis' 'recovered' @{cacheHits=Query 'cache_hit_total{instance="app-a"}';readiness='UP'}
Wait-For 'both targets restored' {(Number 'sum(up{job="las-backend"})') -eq 2} 90
Save 'targets-recovered.json' (Invoke-RestMethod ($prom+'/api/v1/targets'))
Logs 'recovered'

$secretValues=@($settings.MYSQL_PASSWORD,$settings.MYSQL_ROOT_PASSWORD,$settings.RABBITMQ_PASSWORD,$settings.JWT_SECRET_BASE64,$settings.METRICS_PASSWORD,$settings.GRAFANA_PASSWORD,$state.password)
foreach($account in $state.accounts.PSObject.Properties){$secretValues+= $account.Value.token}
$safe=$true;$structured=0;$correlated=@()
foreach($file in Get-ChildItem -LiteralPath $evidence -Filter '*app-*.log'){
    $content=[IO.File]::ReadAllText($file.FullName)
    foreach($secret in $secretValues){if($secret -and $content.Contains($secret)){$safe=$false}}
    foreach($line in $content -split '\r?\n'){
        if(-not $line.StartsWith('{')){continue}
        try{$log=$line | ConvertFrom-Json}catch{continue}
        if($log.message -eq 'Business operation completed'){
            foreach($name in @('timestamp','level','service','instance','traceId','spanId','requestId','businessOperation','duration','errorCode')){Check ($null -ne $log.$name) "Missing structured business log field $name"}
            $structured++
            if($log.traceId -eq $state.traceId){$correlated+=$log}
        }
    }
}
Check $safe 'Sensitive credential found in logs (value suppressed)'
Check ($structured -gt 0 -and $correlated.Count -gt 0) 'No structured/correlated business logs'
Save 'structured-log-evidence.json' @{sensitiveValuesFound=0;structuredBusinessLogs=$structured;linkedLogs=$correlated}
Write-Output 'Day25 fault experiments, three alerts, controlled replay, Redis fallback and log checks completed'
