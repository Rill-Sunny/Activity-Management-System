# Dot-sourced by run-day25-tests.ps1 in the isolated Day 25 Compose project.
$accounts=@{};$password='Day25-'+[Guid]::NewGuid().ToString('N')
foreach($name in @('publisher','buyer','competitor','admin')){
    $registered=Post $appA '/auth/register' '' @{username='day25_'+$name;password=$password}
    if($name -in @('publisher','admin')){[void](Sql "UPDATE app_user SET role='$($name.ToUpperInvariant())' WHERE id=$($registered.id);")}
    $login=Post $appB '/auth/login' '' @{username='day25_'+$name;password=$password}
    $accounts[$name]=@{id=[long]$registered.id;token=$login.accessToken}
}
$buyer=$accounts.buyer.token;$other=$accounts.competitor.token;$publisher=$accounts.publisher.token
$seed="INSERT INTO activity(activity_id,publisher_id,name,type,status,open_time,close_time) VALUES(250001,$($accounts.publisher.id),'Day25 observability','PAID','PUBLISHED',NOW(3)-INTERVAL 1 DAY,NOW(3)+INTERVAL 1 DAY);"
foreach($n in 1..4){
    $id=250000+$n;$capacity=if($n -eq 1){1}else{3}
    $seed+="INSERT INTO activity_session(activity_session_id,activity_id,start_time,end_time,capacity,status,max_tickets_per_user,seat_mode) VALUES($id,250001,NOW(3)+INTERVAL 20 DAY,NOW(3)+INTERVAL 21 DAY,$capacity,'SCHEDULED',$capacity,'AUTO_ASSIGN');"
    $seed+="INSERT INTO session_zone(zone_id,session_id,name,zone_type,price,total_amount,remains_amount) VALUES($id,$id,'Observation zone $n','PAID',100.00,$capacity,$capacity);"
    foreach($seat in 1..$capacity){$seed+="INSERT INTO session_seat(zone_id,row_no,seat_no,allocation_priority,allocation_status) VALUES($id,1,$seat,$seat,'AVAILABLE');"}
}
$seed+=@"
INSERT INTO product(product_id,publisher_id,activity_id,name,status,qualification_type,qualification_scope,qualification_session_id,refund_policy_template,purchase_limit,first_on_sale_at)
VALUES(250001,$($accounts.publisher.id),250001,'Ticket gift box','ON_SALE','TICKET_REQUIRED','SESSION',250002,'STANDARD_7D',2,NOW(3)),
(250002,$($accounts.publisher.id),250001,'Ordinary shirt','ON_SALE','PUBLIC',NULL,NULL,'STANDARD_7D',3,NOW(3)),
(250003,$($accounts.publisher.id),250001,'Last stock','ON_SALE','PUBLIC',NULL,NULL,'STANDARD_7D',10,NOW(3)),
(250004,$($accounts.publisher.id),250001,'Shared limit','ON_SALE','PUBLIC',NULL,NULL,'STANDARD_7D',2,NOW(3));
INSERT INTO sku(sku_id,product_id,sku_name,price,total_stock,remains_stock) VALUES
(250001,250001,'Gift',20,5,5),(250002,250002,'Shirt',20,5,5),(250003,250003,'Last',20,1,1),
(250004,250004,'Black',20,3,3),(250005,250004,'White',20,3,3);
"@
[void](Sql $seed)
function Ticket([int]$session,[string]$token,[string]$key) {
    Post $appA '/ticket-orders' $token @{sessionId=$session;requestId=$key;items=@(@{zoneId=$session;allocationMode='AUTO_ASSIGN';quantity=1;acceptNonAdjacent=$true})}
}
function Product([long[]]$skus,[string]$token,[string]$key) {
    Post $appA '/product-orders/confirm' $token @{requestId=$key;items=@($skus | ForEach-Object {@{skuId=$_;quantity=1}})}
}

# Populate both cache hits and misses before sampling a cold-start counter baseline.
foreach($base in @($appA,$appB)){
    foreach($round in 1..2){
        Check ((Api $base '/activities/250001' '' $null 'GET').status -eq 200) 'Activity detail unavailable'
        Check ((Api $base '/products/250002' '' $null 'GET').status -eq 200) 'Product detail unavailable'
    }
}
Wait-For 'initial cache metrics sampled' { (Number 'sum(cache_hit_total{job="las-backend"})') -gt 0 }
Save 'metrics-before.json' @{cache=Query 'cache_hit_total{job="las-backend"}';payments=Query 'payment_success_total{job="las-backend"}';refunds=Query 'refund_success_total{job="las-backend"}'}

$winner=Ticket 250001 $buyer 'day25-ticket-stock-win';$loser=Ticket 250001 $other 'day25-ticket-stock-lose'
[void](Post $appA "/ticket-orders/$($winner.orderId)/pay" $buyer)
$stockTrace=[Guid]::NewGuid().ToString('N')
$stockFailure=Api $appB "/ticket-orders/$($loser.orderId)/pay" $other $null 'POST' $stockTrace
Check ($stockFailure.status -eq 409 -and $stockFailure.body.code -eq 'INSUFFICIENT_INVENTORY') 'Ticket stock rejection must be a controlled 409'
$pwin=Product @(250003) $buyer 'day25-product-stock-win';$plose=Product @(250003) $other 'day25-product-stock-lose'
[void](Post $appA "/product-orders/$($pwin.orderId)/pay" $buyer)
$productStockTrace=[Guid]::NewGuid().ToString('N')
$productFailure=Api $appB "/product-orders/$($plose.orderId)/pay" $other $null 'POST' $productStockTrace
Check ($productFailure.status -eq 409 -and $productFailure.body.code -eq 'PRODUCT_ORDER_OUT_OF_STOCK') 'Product stock rejection must be a controlled 409'
$quotaOrders=@(Product @(250004) $buyer 'quota-a';Product @(250005) $buyer 'quota-b';Product @(250004) $buyer 'quota-c')
[void](Post $appA "/product-orders/$($quotaOrders[0].orderId)/pay" $buyer)
[void](Post $appB "/product-orders/$($quotaOrders[1].orderId)/pay" $buyer)
$quotaFailure=Api $appA "/product-orders/$($quotaOrders[2].orderId)/pay" $buyer
Check ($quotaFailure.status -eq 409 -and $quotaFailure.body.code -eq 'PRODUCT_PURCHASE_LIMIT_EXCEEDED') 'Shared limit was not a controlled rejection'

$ticketOrder=Ticket 250002 $buyer 'day25-linkage-ticket'
$paid=Post $appA "/ticket-orders/$($ticketOrder.orderId)/pay" $buyer
$ticketId=[long]$paid.tickets[0].ticketId
$mixed=Product @(250001,250002) $buyer 'day25-mixed-order'
[void](Post $appB "/product-orders/$($mixed.orderId)/pay" $buyer)
$refund=Post $appA '/ticket-refunds' $buyer @{orderId=$ticketOrder.orderId;ticketIds=@($ticketId);requestId='day25-trace-refund';reason='Observability acceptance'}
$traceId=[Guid]::NewGuid().ToString('N')
$processed=Api $appA "/ticket-refunds/$($refund.refundId)/process" $buyer $null 'POST' $traceId
Check ($processed.status -eq 200) 'Traced refund failed'
$eventId=Sql "SELECT event_id FROM outbox_event WHERE event_type='TICKET_QUALIFICATION_CHANGED' AND aggregate_id=$($refund.refundId);"
Wait-For 'qualification aftersale commits' { (Sql "SELECT COUNT(*) FROM ticket_qualification_after_sale_task WHERE source_event_id='$eventId' AND status='SUCCEEDED';") -eq '1' }
Check ((Sql "SELECT refunded_quantity FROM product_order_item WHERE order_id=$($mixed.orderId) AND sku_id=250001;") -eq '1') 'Limited product was not refunded'
Check ((Sql "SELECT refunded_quantity FROM product_order_item WHERE order_id=$($mixed.orderId) AND sku_id=250002;") -eq '0') 'Ordinary product was incorrectly refunded'

$rescheduleOrder=Ticket 250003 $buyer 'day25-reschedule-ticket'
$reschedulePaid=Post $appB "/ticket-orders/$($rescheduleOrder.orderId)/pay" $buyer
$changed=Post $appA '/publisher/sessions/250003/reschedules' $publisher @{requestId='day25-reschedule';newStartTime=[DateTimeOffset]::Now.AddDays(22).ToString('o');newEndTime=[DateTimeOffset]::Now.AddDays(23).ToString('o');reason='Observation of reschedule refund'}
$special=Post $appB "/reschedules/$($changed.eventId)/special-refunds" $buyer @{requestId='day25-special-refund';ticketIds=@($reschedulePaid.tickets[0].ticketId)}
[void](Post $appA "/ticket-refunds/$($special.refunds[0].refundId)/process" $buyer)

$cancelOrder=Ticket 250004 $buyer 'day25-cancel-paid'
[void](Post $appB "/ticket-orders/$($cancelOrder.orderId)/pay" $buyer)
[void](Ticket 250004 $other 'day25-cancel-pending')
$cancelTrace=[Guid]::NewGuid().ToString('N')
[void](Post $appA '/publisher/sessions/250004/cancel' $publisher @{requestId='day25-cancel';reason='Observation of cancellation processing'} $cancelTrace)
Wait-For 'cancellation converges' { (Sql "SELECT COUNT(*) FROM cancellation_batch WHERE status='COMPLETED';") -eq '1' } 180
Wait-For 'Outbox sent' { (Sql "SELECT COUNT(*) FROM outbox_event WHERE status<>'SENT';") -eq '0' }

Save 'business.json' @{ticketStockFailure=$stockFailure;productStockFailure=$productFailure;quotaFailure=$quotaFailure;traceId=$traceId;refundId=$refund.refundId;eventId=$eventId;requestId=$processed.requestId;mixedOrder=$mixed.orderId;ordinaryRefunded=0;limitedRefunded=1;stockTrace=$stockTrace;productStockTrace=$productStockTrace;cancelTrace=$cancelTrace;rescheduleRefundId=$special.refunds[0].refundId;cancelledOrderId=$cancelOrder.orderId}
# Local ignored state only. Never include these credentials in public reports or diagnostics.
Save 'state.private.json' @{accounts=$accounts;password=$password;paidOrderId=$winner.orderId;paidProductOrderId=$pwin.orderId;refundedId=$refund.refundId;traceId=$traceId;eventId=$eventId}
Write-Output 'Day25 business fixtures and all three refund policies completed'
