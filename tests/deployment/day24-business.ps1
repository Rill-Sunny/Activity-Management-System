param([string[]]$Compose, [string]$AppA, [string]$AppB, [hashtable]$Settings, [string]$Evidence, [string]$BrokerUrl)
. (Join-Path $PSScriptRoot '../../deploy/scripts/common.ps1')
$client=[Net.Http.HttpClient]::new()
$client.Timeout=[TimeSpan]::FromSeconds(25)
$results=[Collections.Generic.List[object]]::new()
function Assert-True([bool]$condition,[string]$message) {if(-not $condition){throw $message}}
function Sql([string]$text) {Invoke-DatabaseSql $Compose $text}
function Calls([object[]]$requests) {
    $pending=@()
    foreach($spec in $requests){
        $request=[Net.Http.HttpRequestMessage]::new([Net.Http.HttpMethod]::new($spec.method),$spec.url)
        if($spec.token){$request.Headers.Authorization=[Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer',$spec.token)}
        if($null -ne $spec.body){$request.Content=[Net.Http.StringContent]::new(($spec.body | ConvertTo-Json -Depth 15 -Compress),[Text.Encoding]::UTF8,'application/json')}
        $pending+=@{request=$request;task=$client.SendAsync($request)}
    }
    foreach($call in $pending){
        $response=$call.task.GetAwaiter().GetResult()
        try {
            $text=$response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
            $body=if($text){$text | ConvertFrom-Json}else{$null}
            [pscustomobject]@{status=[int]$response.StatusCode;body=$body}
        } finally {$response.Dispose();$call.request.Dispose()}
    }
}
function Request([string]$base,[string]$path,[string]$token,$body=$null,[string]$method='POST') {
    @{method=$method;url=$base+$path;token=$token;body=$body}
}
function Post([string]$base,[string]$path,[string]$token,$body=$null) {
    $r=Calls @((Request $base $path $token $body))
    Assert-True ($r.status -ge 200 -and $r.status -lt 300) "HTTP $path failed: $($r.status) $($r.body | ConvertTo-Json -Compress)"
    return $r.body
}
function Wait-Condition([scriptblock]$condition,[string]$description,[int]$seconds=90) {
    $deadline=[DateTime]::UtcNow.AddSeconds($seconds)
    do {if(& $condition){return}; Start-Sleep -Milliseconds 500}while([DateTime]::UtcNow -lt $deadline)
    throw "Timed out: $description"
}
function Record([string]$name,$detail) {$results.Add(@{scenario=$name;status='PASSED';detail=$detail})}
function TicketBody([long]$session,[long]$zone,[string]$key) {
    @{sessionId=$session;requestId=$key;items=@(@{zoneId=$zone;allocationMode='AUTO_ASSIGN';quantity=1;acceptNonAdjacent=$true})}
}
function Broker([string]$method,[string]$path,$body) {
    $encoded=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($Settings.RABBITMQ_USERNAME+':'+$Settings.RABBITMQ_PASSWORD))
    $params=@{Uri=$BrokerUrl+$path;Method=$method;Headers=@{Authorization='Basic '+$encoded};TimeoutSec=20}
    if($null -ne $body){$params.ContentType='application/json';$params.Body=$body | ConvertTo-Json -Depth 15 -Compress}
    Invoke-RestMethod @params
}
try {
    $password='Day24-'+[Guid]::NewGuid().ToString('N')
    $accounts=@{}
    foreach($name in @('publisher','buyer','competitor')){
        $username='day24_'+$name
        $registered=Post $AppA '/auth/register' '' @{username=$username;password=$password}
        if($name -eq 'publisher'){[void](Sql "UPDATE app_user SET role='PUBLISHER' WHERE id=$($registered.id);")}
        $login=Post $AppB '/auth/login' '' @{username=$username;password=$password}
        $accounts[$name]=@{id=[long]$registered.id;token=$login.accessToken}
    }
    $buyer=$accounts.buyer.token; $other=$accounts.competitor.token; $publisher=$accounts.publisher.token
    $seed=@"
INSERT INTO activity(activity_id,publisher_id,name,type,status,open_time,close_time)
VALUES(240001,$($accounts.publisher.id),'Day24 acceptance','PAID','PUBLISHED',NOW(3)-INTERVAL 1 DAY,NOW(3)+INTERVAL 1 DAY);
"@
    for($n=1;$n -le 12;$n++){
        $id=240000+$n;$capacity=if($n -eq 2){1}else{3}
        $seed+="INSERT INTO activity_session(activity_session_id,activity_id,start_time,end_time,capacity,status,max_tickets_per_user,seat_mode) VALUES($id,240001,NOW(3)+INTERVAL 20 DAY,NOW(3)+INTERVAL 21 DAY,$capacity,'SCHEDULED',$capacity,'AUTO_ASSIGN');`n"
        $seed+="INSERT INTO session_zone(zone_id,session_id,name,zone_type,price,total_amount,remains_amount) VALUES($id,$id,'Zone $n','PAID',100.00,$capacity,$capacity);`n"
        for($seat=1;$seat -le $capacity;$seat++){
            $seed+="INSERT INTO session_seat(zone_id,row_no,seat_no,allocation_priority,allocation_status) VALUES($id,1,$seat,$seat,'AVAILABLE');`n"
        }
    }
    $seed+=@"
INSERT INTO product(product_id,publisher_id,activity_id,name,status,qualification_type,refund_policy_template,purchase_limit,first_on_sale_at)
VALUES(240001,$($accounts.publisher.id),240001,'Shared quota','ON_SALE','PUBLIC','STANDARD_7D',2,NOW(3));
INSERT INTO sku(sku_id,product_id,sku_name,price,total_stock,remains_stock) VALUES(240001,240001,'A',20,5,5),(240002,240001,'B',20,5,5);
"@
    [void](Sql $seed)

    $body=TicketBody 240001 240001 'day24-duplicate-order'
    $r=@(Calls @((Request $AppA '/ticket-orders' $buyer $body),(Request $AppB '/ticket-orders' $buyer $body)))
    Assert-True (@($r | Where-Object status -NotIn @(200,201)).Count -eq 0) 'Duplicate create HTTP failed'
    Assert-True ($r[0].body.orderId -eq $r[1].body.orderId) 'Duplicate order IDs differ'
    Assert-True ((Sql "SELECT COUNT(*) FROM ticket_order WHERE request_id='day24-duplicate-order';") -eq '1') 'Duplicate business order'
    $order=[long]$r[0].body.orderId
    Record 'duplicate-order-across-instances' @{orderId=$order;responses=@($r.status)}

    $r=@(Calls @((Request $AppA "/ticket-orders/$order/pay" $buyer),(Request $AppB "/ticket-orders/$order/pay" $buyer)))
    $r | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $Evidence 'duplicate-payment-responses.json') -Encoding utf8
    Assert-True (@($r | Where-Object status -EQ 200).Count -ge 1) 'Concurrent payment has no success'
    foreach($response in $r){
        Assert-True ($response.status -eq 200 -or ($response.status -eq 409 -and $response.body.code -eq 'REQUEST_IN_PROGRESS')) 'Concurrent payment unexpected response'
    }
    [void](Post $AppA "/ticket-orders/$order/pay" $buyer)
    [void](Post $AppB "/ticket-orders/$order/pay" $buyer)
    Assert-True ((Sql "SELECT COUNT(*) FROM ticket t JOIN ticket_order_item i ON i.order_item_id=t.order_item_id WHERE i.order_id=$order;") -eq '1') 'Duplicate ticket issued'
    Assert-True ((Sql 'SELECT remains_amount FROM session_zone WHERE zone_id=240001;') -eq '2') 'Payment inventory deducted twice'
    Assert-True ((Sql "SELECT valid_count FROM user_session_purchase WHERE user_id=$($accounts.buyer.id) AND session_id=240001;") -eq '1') 'Payment quota duplicated'
    $paidResponse=$r | Where-Object status -EQ 200 | Select-Object -First 1
    $ticket=[long]$paidResponse.body.tickets[0].ticketId
    Record 'same-order-payment-across-instances' @{orderId=$order;ticketId=$ticket;remaining=2;quota=1}

    $first=Post $AppA '/ticket-orders' $buyer (TicketBody 240002 240002 'last-seat-a')
    $second=Post $AppB '/ticket-orders' $other (TicketBody 240002 240002 'last-seat-b')
    $r=@(Calls @((Request $AppA "/ticket-orders/$($first.orderId)/pay" $buyer),(Request $AppB "/ticket-orders/$($second.orderId)/pay" $other)))
    Assert-True (@($r | Where-Object status -EQ 200).Count -eq 1) 'Last seat did not have exactly one winner'
    Assert-True (@($r | Where-Object status -EQ 409).Count -eq 1) 'Last seat loser is not a controlled conflict'
    Assert-True ((Sql 'SELECT remains_amount FROM session_zone WHERE zone_id=240002;') -eq '0') 'Last seat inventory wrong'
    Record 'last-seat-across-instances' @{responses=@($r.status);remaining=0}

    $refundBody=@{requestId='day24-refund';orderId=$order;ticketIds=@($ticket);reason='Day24 duplicate refund'}
    $r=@(Calls @((Request $AppA '/ticket-refunds' $buyer $refundBody),(Request $AppB '/ticket-refunds' $buyer $refundBody)))
    Assert-True (@($r | Where-Object status -NotIn @(200,201)).Count -eq 0) 'Refund application failed'
    Assert-True ($r[0].body.refundId -eq $r[1].body.refundId) 'Duplicate refund requests'
    $refund=[long]$r[0].body.refundId
    $r=@(Calls @((Request $AppA "/ticket-refunds/$refund/process" $buyer),(Request $AppB "/ticket-refunds/$refund/process" $buyer)))
    Assert-True (@($r | Where-Object status -EQ 200).Count -ge 1) 'Duplicate refund has no success'
    foreach($response in $r){
        Assert-True ($response.status -eq 200 -or ($response.status -eq 409 -and $response.body.code -eq 'REQUEST_IN_PROGRESS')) 'Concurrent refund unexpected response'
    }
    [void](Post $AppA "/ticket-refunds/$refund/process" $buyer)
    [void](Post $AppB "/ticket-refunds/$refund/process" $buyer)
    Assert-True ((Sql 'SELECT remains_amount FROM session_zone WHERE zone_id=240001;') -eq '3') 'Refund inventory restored incorrectly'
    Assert-True ((Sql "SELECT valid_count FROM user_session_purchase WHERE user_id=$($accounts.buyer.id) AND session_id=240001;") -eq '0') 'Refund quota restored incorrectly'
    Assert-True ((Sql "SELECT COUNT(*) FROM ticket_refund_item WHERE ticket_id=$ticket AND status='SUCCEEDED';") -eq '1') 'Duplicate successful refund'
    Record 'duplicate-refund-across-instances' @{refundId=$refund;remaining=3;quota=0}

    $productOrders=@()
    foreach($index in 0..2){
        $sku=if($index -eq 2){240002}else{240001}
        $productOrders+=Post $AppA '/product-orders/confirm' $buyer @{requestId="day24-product-$index";items=@(@{skuId=$sku;quantity=1})}
    }
    [void](Post $AppA "/product-orders/$($productOrders[0].orderId)/pay" $buyer)
    $r=@(Calls @((Request $AppA "/product-orders/$($productOrders[1].orderId)/pay" $buyer),(Request $AppB "/product-orders/$($productOrders[2].orderId)/pay" $buyer)))
    Assert-True (@($r | Where-Object status -EQ 200).Count -eq 1) 'Shared Product limit had wrong number of winners'
    $losers=@($r | Where-Object status -NE 200)
    Assert-True ($losers.Count -eq 1 -and $losers[0].status -eq 409 -and $losers[0].body.code -eq 'PRODUCT_PURCHASE_LIMIT_EXCEEDED') 'Shared Product limit loser uncontrolled'
    Assert-True ((Sql "SELECT valid_count FROM user_product_purchase WHERE user_id=$($accounts.buyer.id) AND product_id=240001;") -eq '2') 'Product limit exceeded'
    Assert-True ((Sql 'SELECT SUM(remains_stock) FROM sku WHERE product_id=240001;') -eq '8') 'Product stock mismatch'
    Record 'shared-product-quota-across-instances' @{responses=@($r.status);validCount=2;remainingTotal=8}

    $cancelOutcomes=@()
    foreach($n in 3..12){
        $session=240000+$n
        $pending=Post $AppB '/ticket-orders' $buyer (TicketBody $session $session "cancel-race-$n")
        $r=@(Calls @((Request $AppA "/publisher/sessions/$session/cancel" $publisher @{requestId="cancel-$n";reason='Day24 race'}),
                        (Request $AppB "/ticket-orders/$($pending.orderId)/pay" $buyer)))
        Assert-True ($r[0].status -eq 202) 'Session cancellation failed'
        Assert-True ($r[1].status -in @(200,409)) 'Cancel/payment race produced an illegal response'
        $cancelOutcomes+=@{sessionId=$session;paymentStatus=$r[1].status}
    }
    Wait-Condition { (Sql "SELECT COUNT(*) FROM cancellation_batch WHERE status<>'COMPLETED';") -eq '0' } 'cancellation batches converge' 150
    Assert-True ((Sql "SELECT COUNT(*) FROM ticket_order WHERE session_id BETWEEN 240003 AND 240012 AND status NOT IN ('REFUNDED','CLOSED');") -eq '0') 'Cancelled order remained payable or paid'
    Assert-True ((Sql 'SELECT SUM(remains_amount) FROM session_zone WHERE session_id BETWEEN 240003 AND 240012;') -eq '30') 'Cancellation resource restore mismatch'
    Record 'cancel-versus-payment-across-instances' $cancelOutcomes

    Wait-Condition { (Sql "SELECT COUNT(*) FROM outbox_event WHERE status<>'SENT';") -eq '0' } 'both relays drain outbox'
    Wait-Condition { (Sql "SELECT COUNT(*) FROM outbox_event e LEFT JOIN consumer_process p ON p.event_id=e.event_id WHERE e.event_type='PAYMENT_SUCCEEDED' AND p.event_id IS NULL;") -eq '0' } 'payment notifications consumed'
    $outboxCount=[int](Sql 'SELECT COUNT(*) FROM outbox_event;')
    Record 'shared-outbox-relays' @{sent=$outboxCount;unpublished=0}

    $event=(Sql "SELECT JSON_OBJECT('id',event_id,'payload',payload) FROM outbox_event WHERE event_type='PAYMENT_SUCCEEDED' ORDER BY created_at LIMIT 1;") | ConvertFrom-Json
    $payload=if($event.payload -is [string]){$event.payload}else{$event.payload | ConvertTo-Json -Depth 10 -Compress}
    foreach($i in 1..20){
        $published=Broker POST '/api/exchanges/%2F/las.events.exchange/publish' @{
            routing_key='notification.payment.success';payload=$payload;payload_encoding='string';
            properties=@{delivery_mode=2;message_id=$event.id;headers=@{'x-event-id'=$event.id}}
        }
        Assert-True $published.routed 'Duplicate message was not routed'
    }
    Wait-Condition {
        $queue=Broker GET '/api/queues/%2F/las.notification.payment' $null
        $queue.messages -eq 0 -and $queue.messages_unacknowledged -eq 0
    } 'duplicate delivery queue drains'
    Assert-True ((Sql "SELECT COUNT(*) FROM notification WHERE event_id='$($event.id)';") -eq '1') 'Duplicate notification effect'
    Assert-True ((Sql "SELECT COUNT(*) FROM consumer_process WHERE event_id='$($event.id)' AND consumer_name='payment-notification-v1';") -eq '1') 'Duplicate consumer marker'
    Assert-True ((Sql 'SELECT COUNT(*) FROM consumer_failure;') -eq '0') 'Consumer failure remains'
    Record 'duplicate-consumer-delivery-across-instances' @{eventId=$event.id;redeliveries=20;notifications=1;markers=1}

    $queue=Broker GET '/api/queues/%2F/las.notification.payment' $null
    Assert-True ($queue.consumers -ge 2) 'Both application consumers must be connected'
    $queue.consumer_details | Select-Object consumer_tag,ack_required,prefetch_count,channel_details |
        ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $Evidence 'rabbit-consumers.json') -Encoding utf8
    $relayEvidence=@{}
    foreach($name in @('app-a','app-b')){
        $logs=Invoke-Program docker ($Compose+@('logs','--no-color',$name))
        $logs.Output | Set-Content -LiteralPath (Join-Path $Evidence "$name-business.log") -Encoding utf8
        $confirmed=[regex]::Matches($logs.Output,'Outbox event confirmed by RabbitMQ').Count
        Assert-True ($confirmed -gt 0) "Relay $name did not publish an event"
        $relayEvidence[$name]=$confirmed
    }
    $relayEvidence | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $Evidence 'relay-instances.json') -Encoding utf8

    # Leave one persistent message in an unconsumed queue for down/up verification.
    [void](Broker PUT '/api/queues/%2F/day24.persistence' @{durable=$true;auto_delete=$false;arguments=@{}})
    [void](Broker POST '/api/exchanges/%2F/amq.default/publish' @{routing_key='day24.persistence';payload='day24-persistent-message';payload_encoding='string';properties=@{delivery_mode=2}})
    @{buyerId=$accounts.buyer.id;orderId=$order;ticketId=$ticket;refundId=$refund;outboxEvents=$outboxCount} |
        ConvertTo-Json | Set-Content -LiteralPath (Join-Path $Evidence 'business-identifiers.json') -Encoding utf8
} finally {
    $results | ConvertTo-Json -Depth 15 | Set-Content -LiteralPath (Join-Path $Evidence 'business-results.json') -Encoding utf8
    $client.Dispose()
}
