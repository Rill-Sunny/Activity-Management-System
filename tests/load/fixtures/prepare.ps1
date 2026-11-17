param([Parameter(Mandatory)][string]$RunDirectory,[Parameter(Mandatory)][string]$OutputDirectory,
    [ValidateSet('S01','S02','S03','S04','S05','S06','S07','S08','S09','FAULT')][string]$Scenario,[int]$Vus=10,[switch]$LastSlot)
. (Join-Path $PSScriptRoot '../../system/common.ps1')
$lab=Open-Lab $RunDirectory
[void][IO.Directory]::CreateDirectory($OutputDirectory)
function Token([long]$id,[string]$role='USER'){
    function B64([byte[]]$bytes){[Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+','-').Replace('/','_')}
    $now=[DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
    $head=B64 ([Text.Encoding]::UTF8.GetBytes('{"alg":"HS256","typ":"JWT"}'))
    $body=B64 ([Text.Encoding]::UTF8.GetBytes((@{sub=[string]$id;role=$role;iat=$now;exp=$now+28800}|ConvertTo-Json -Compress)))
    $hmac=[Security.Cryptography.HMACSHA256]::new([Convert]::FromBase64String($lab.settings.JWT_SECRET_BASE64))
    try{$head+'.'+$body+'.'+(B64 ($hmac.ComputeHash([Text.Encoding]::ASCII.GetBytes($head+'.'+$body))))}finally{$hmac.Dispose()}
}
$publisher=270001;$admin=270002;$activity=270100;$session=270101;$zone=270102;$product=270201;$skuA=270211;$skuB=270212
$capacity=if($Scenario -eq 'S03' -or $LastSlot){1}elseif($Scenario -eq 'S04'){$Vus*6}else{100}
$type=if($Scenario -eq 'S01'){'FREE'}else{'PAID'}
$mode=if($Scenario -eq 'S03'){'SELECT_SEAT'}else{'AUTO_ASSIGN'}
$maximum=if($Scenario -eq 'FAULT'){100}elseif($Scenario -in @('S04','S07')){4}else{1}
$productLimit=if($Scenario -eq 'S06'){2}else{10000}
$stockB=if($Scenario -eq 'S05'){1}else{500}
$sql="INSERT INTO app_user(id,username,password_hash,role) VALUES($publisher,'load_publisher','fixture-login-disabled','PUBLISHER'),($admin,'load_admin','fixture-login-disabled','ADMIN');`n"
for($i=1;$i -le 300;$i++){$sql+="INSERT INTO app_user(id,username,password_hash,role) VALUES($(271000+$i),'load_user_$i','fixture-login-disabled','USER');`n"}
$sql+="INSERT INTO activity(activity_id,publisher_id,name,type,status,open_time,close_time) VALUES($activity,$publisher,'Isolated load activity','$type','PUBLISHED',NOW(3)-INTERVAL 1 DAY,NOW(3)+INTERVAL 1 DAY);`n"
$limits=if($type -eq 'FREE'){'NULL,NULL'}else{"$maximum,'$mode'"}
$sql+="INSERT INTO activity_session(activity_session_id,activity_id,start_time,end_time,capacity,status,max_tickets_per_user,seat_mode) VALUES($session,$activity,NOW(3)+INTERVAL 20 DAY,NOW(3)+INTERVAL 21 DAY,$capacity,'SCHEDULED',$limits);`n"
if($type -eq 'PAID'){
    $sql+="INSERT INTO session_zone(zone_id,session_id,name,zone_type,price,total_amount,remains_amount) VALUES($zone,$session,'Paid zone','PAID',100.00,$capacity,$capacity);`n"
    for($i=1;$i -le $capacity;$i++){$sql+="INSERT INTO session_seat(zone_id,row_no,seat_no,allocation_priority) VALUES($zone,1,$i,$i);`n"}
}else{$sql+="INSERT INTO free_session_quota(session_id,capacity,remaining) VALUES($session,$capacity,$capacity);`n"}
$sql+="INSERT INTO product(product_id,publisher_id,activity_id,name,status,qualification_type,refund_policy_template,purchase_limit,first_on_sale_at) VALUES($product,$publisher,$activity,'Isolated T shirt','ON_SALE','PUBLIC','STANDARD_7D',$productLimit,NOW(3));`n"
$sql+="INSERT INTO sku(sku_id,product_id,sku_name,price,total_stock,remains_stock) VALUES($skuA,$product,'BLACK-M',20.00,1000,1000),($skuB,$product,'WHITE-L',20.00,$stockB,$stockB);`n"
[void](Lab-Sql $lab $sql)
$tokens=@(1..300|ForEach-Object{Token (271000+$_)})
$work=[Collections.Generic.List[object]]::new()
$seat=if($type -eq 'PAID'){[long](Lab-Sql $lab "SELECT MIN(seat_id) FROM session_seat WHERE zone_id=$zone;")}else{0}
function TicketOrder([string]$token,[string]$key,[int]$qty=1){
    $item=@{zoneId=$zone;allocationMode=$mode;quantity=$qty;acceptNonAdjacent=($mode -ne 'SELECT_SEAT')};if($mode -eq 'SELECT_SEAT'){$item.requestedSeatId=$seat}
    Lab-Post $lab '/ticket-orders' $token @{requestId=$key;sessionId=$session;items=@($item)}
}
function ProductOrder([string]$token,[string]$key,$items){Lab-Post $lab '/product-orders/confirm' $token @{requestId=$key;items=$items}}
switch($Scenario){
    {$_ -in @('S01','S08')} {for($i=0;$i -lt 150;$i++){$work.Add(@{token=$tokens[$i];requestId="reserve-$i"})}}
    {$_ -in @('S02','S03')} {for($i=0;$i -lt 150;$i++){$order=TicketOrder $tokens[$i] "ticket-$i";$work.Add(@{token=$tokens[$i];orderId=$order.orderId})}}
    'S04' {for($i=0;$i -lt $Vus;$i++){$a=TicketOrder $tokens[$i] "quota-$i-a" 3;$b=TicketOrder $tokens[$i] "quota-$i-b" 3;$work.Add(@{token=$tokens[$i];orders=@($a.orderId,$b.orderId)})}}
    'S05' {
        for($i=0;$i -lt 150;$i++){$order=ProductOrder $tokens[$i] "multi-$i" @(@{skuId=$skuA;quantity=2},@{skuId=$skuB;quantity=1});$work.Add(@{token=$tokens[$i];orderId=$order.orderId})}
        $competing=ProductOrder $tokens[299] 'competitor' @(@{skuId=$skuB;quantity=1});[void](Lab-Post $lab "/product-orders/$($competing.orderId)/pay" $tokens[299])
    }
    'S06' {for($i=0;$i -lt $Vus;$i++){$a=ProductOrder $tokens[$i] "product-$i-a" @(@{skuId=$skuA;quantity=2});$b=ProductOrder $tokens[$i] "product-$i-b" @(@{skuId=$skuB;quantity=2});$work.Add(@{token=$tokens[$i];orders=@($a.orderId,$b.orderId)})}}
    'S07' {
        $pending=TicketOrder $tokens[0] 'duplicate-payment';$paid=TicketOrder $tokens[0] 'duplicate-refund'
        $tickets=Lab-Post $lab "/ticket-orders/$($paid.orderId)/pay" $tokens[0]
        $refund=Lab-Post $lab '/ticket-refunds' $tokens[0] @{requestId='duplicate-refund';orderId=$paid.orderId;ticketIds=@($tickets.tickets[0].ticketId);reason='Load duplicate refund'}
        for($i=0;$i -lt 150;$i++){$work.Add(@{token=$tokens[0];operation=$(if($i%2 -eq 0){'pay'}else{'refund'});path=$(if($i%2 -eq 0){"/ticket-orders/$($pending.orderId)/pay"}else{"/ticket-refunds/$($refund.refundId)/process"})})}
    }
    'S09' {
        for($i=0;$i -lt 100;$i++){$order=TicketOrder $tokens[$i] "cancel-$i";[void](Lab-Post $lab "/ticket-orders/$($order.orderId)/pay" $tokens[$i])}
        for($i=0;$i -lt $Vus;$i++){$work.Add(@{token=$tokens[$i]})}
    }
    'FAULT' {$work.Add(@{token=$tokens[0]})}
}
$fixture=@{scenario=$Scenario;activityId=$activity;sessionId=$session;zoneId=$zone;seatId=$seat;productId=$product;skuA=$skuA;skuB=$skuB;capacity=$capacity;users=300;publisherId=$publisher;publisherToken=(Token $publisher 'PUBLISHER');adminToken=(Token $admin 'ADMIN');tokens=$tokens;work=@($work);createdAt=[DateTimeOffset]::Now.ToString('o')}
Save-Lab (Join-Path $OutputDirectory 'fixture.private.json') $fixture
Save-Lab (Join-Path $OutputDirectory 'fixture-summary.json') @{scenario=$Scenario;users=300;workItems=$work.Count;capacity=$capacity;purchaseLimit=$maximum;productLimit=$productLimit;initialSkuA=1000;initialSkuB=$stockB;ordersPreparedThroughHttp=$true;authentication='HS256 test tokens for real ACTIVE database users; production Security filters enabled';createdAt=$fixture.createdAt}
Lab-Reconcile $lab (Join-Path $OutputDirectory 'before')
