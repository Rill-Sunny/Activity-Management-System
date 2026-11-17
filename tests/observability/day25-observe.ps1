$business=Get-Content (Join-Path $evidence 'business.json') -Raw | ConvertFrom-Json
Wait-For 'business metrics scraped' {
    (Number 'sum(ticket_refund_seconds_count{result="SUCCESS",policy="NORMAL"})') -gt 0 -and
    (Number 'sum(ticket_refund_seconds_count{result="SUCCESS",policy="RESCHEDULE"})') -gt 0 -and
    (Number 'sum(ticket_refund_seconds_count{result="SUCCESS",policy="CANCELLATION"})') -gt 0 -and
    (Number 'sum(product_payment_seconds_count{result="INSUFFICIENT_STOCK"})') -gt 0
}
$targets=Invoke-RestMethod ($prom+'/api/v1/targets')
$apps=@($targets.data.activeTargets | Where-Object {$_.labels.job -eq 'las-backend'})
Check ($apps.Count -eq 2 -and @($apps | Where-Object health -ne 'up').Count -eq 0) 'Both app targets must be UP'
Check (@($apps.labels.instance | Sort-Object -Unique).Count -eq 2) 'Instance labels collided'
Save 'targets-before-faults.json' $targets
$meters=@{}
foreach($name in @('payment_success_total','payment_failure_total','refund_success_total','product_payment_seconds_count','ticket_payment_seconds_count','ticket_refund_seconds_count','mq_consume_seconds_count','cache_hit_total','cache_miss_total','cache_db_fallback_total','cache_invalidation_failure_total','redis_dedupe_rejected_total','outbox_pending','outbox_oldest_age_seconds','cancellation_batch','cancellation_item','rabbitmq_queue_messages')){
    $meters[$name]=Query $name
    Check ($meters[$name].Count -gt 0) "Metric not scraped: $name"
}
Save 'metrics-after-business.json' $meters
$dashboard=Grafana '/api/dashboards/uid/las-backend-overview'
Check ($dashboard.dashboard.title -eq 'LAS Backend Overview') 'Dashboard provisioning failed'
Check ($dashboard.dashboard.panels.Count -eq 21) 'Unexpected dashboard panel count'
$queries=@()
foreach($panel in $dashboard.dashboard.panels){
    $expression=$panel.targets[0].expr.Replace('$__rate_interval','5m')
    $rows=@(Query $expression)
    $queries+=@{panel=$panel.title;query=$expression;series=$rows.Count}
}
Save 'dashboard-query-validation.json' $queries
Save 'dashboard-provisioned.json' $dashboard
Save 'datasources.json' (Grafana '/api/datasources')
Save 'alert-rules.json' (Invoke-RestMethod ($prom+'/api/v1/rules'))
$spans=Read-Trace $business.traceId @('ticket.refund','outbox.publish','mq.consume','product.aftersale')
$refundSpan=$spans | Where-Object name -eq 'ticket.refund' | Select-Object -First 1
$published=$spans | Where-Object { $_.name -eq 'outbox.publish' -and @($_.attributes | Where-Object {$_.key -eq 'messaging.message.id' -and $_.value.stringValue -eq $business.eventId}).Count -gt 0 } | Select-Object -First 1
Check ($null -ne $published -and $published.parentSpanId -eq $refundSpan.spanId) 'Outbox publisher is not parented to the committed refund context'
$consumed=$spans | Where-Object {$_.name -eq 'mq.consume' -and $_.parentSpanId -eq $published.spanId}
Check (@($consumed).Count -gt 0) 'Consumer did not extract the publisher W3C context'
$viaGrafana=Grafana ('/api/datasources/proxy/uid/tempo/api/traces/'+$business.traceId)
Check ($null -ne $viaGrafana.batches) 'Grafana cannot retrieve Tempo trace'
Save 'trace-linkage-summary.json' @{traceId=$business.traceId;eventId=$business.eventId;requestId=$business.requestId;spans=$spans;grafanaQuerySucceeded=$true;durableTraceparent=(Sql "SELECT traceparent FROM outbox_event WHERE event_id='$($business.eventId)';")}
foreach($id in @($business.stockTrace,$business.productStockTrace)){
    $paymentSpans=Read-Trace $id @()
    $businessSpan=$paymentSpans | Where-Object name -in @('ticket.payment','product.payment') | Select-Object -First 1
    # Tempo's protobuf JSON can encode status as an enum name instead of its numeric value.
    Check ($null -ne $businessSpan -and $businessSpan.status.code -notin @(2,'STATUS_CODE_ERROR')) 'Stock rejection was incorrectly marked as a technical span failure'
    Check (@($businessSpan.attributes | Where-Object {$_.key -eq 'business.result' -and $_.value.stringValue -eq 'INSUFFICIENT_STOCK'}).Count -eq 1) 'Stock rejection did not retain its business result'
    Save "stock-$id.json" @{traceId=$id;spans=$paymentSpans}
}
$boundaries=@()
foreach($path in @('/actuator/prometheus','/actuator/metrics')){
    foreach($case in @(@{port=9091;auth='';expected=401},@{port=9091;auth='--auth-no-challenge --user=prometheus --password="$METRICS_PASSWORD"';expected=200},@{port=8080;auth='--auth-no-challenge --user=prometheus --password="$METRICS_PASSWORD"';expected=403})){
        $script='wget -q -S '+$case.auth+' -O /dev/null http://127.0.0.1:'+$case.port+$path
        $result=Invoke-Program docker ($compose+@('exec','-T','app-a','sh','-c',$script)) -AllowFailure
        $codes=@([regex]::Matches($result.Error,'HTTP/1\.1 (\d+)') | ForEach-Object {[int]$_.Groups[1].Value})
        Check ($codes.Count -gt 0 -and $codes[-1] -eq $case.expected) "Management boundary failed: $path port $($case.port)"
        $boundaries+=@{path=$path;port=$case.port;authenticated=[bool]$case.auth;status=$codes[-1]}
    }
}
Save 'management-security.json' $boundaries
Write-Output 'Day25 real metrics, dashboard queries, async trace and management access verified'
