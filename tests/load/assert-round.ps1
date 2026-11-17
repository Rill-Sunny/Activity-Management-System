param([Parameter(Mandatory)][string]$RunDirectory,[Parameter(Mandatory)][string]$OutputDirectory)
. (Join-Path $PSScriptRoot '../system/common.ps1')
$lab=Open-Lab $RunDirectory
$fixture=Get-Content (Join-Path $OutputDirectory 'fixture.private.json') -Raw|ConvertFrom-Json
function Scalar([string]$sql){[long](Lab-Sql $lab $sql)}
$evidence=@{scenario=$fixture.scenario;checkedAt=[DateTimeOffset]::Now.ToString('o')}
$evidence.validTickets=Scalar "SELECT COUNT(*) FROM ticket WHERE status='VALID';"
$evidence.paidOrders=Scalar "SELECT COUNT(*) FROM ticket_order WHERE paid_at IS NOT NULL;"
$evidence.productPaid=Scalar "SELECT COUNT(*) FROM product_order WHERE paid_at IS NOT NULL;"
$evidence.refunds=Scalar "SELECT COUNT(*) FROM ticket_refund_request WHERE status='SUCCEEDED';"
$evidence.skuA=Scalar "SELECT remains_stock FROM sku WHERE sku_id=$($fixture.skuA);"
$evidence.skuB=Scalar "SELECT remains_stock FROM sku WHERE sku_id=$($fixture.skuB);"
try{switch($fixture.scenario){
    'S01' {$evidence.reservations=Scalar "SELECT COUNT(*) FROM free_reservation WHERE status='ACTIVE';";Assert-Lab ($evidence.reservations -eq $fixture.capacity) 'Reservation winners differ from capacity'}
    {$_ -in @('S02','S03')} {Assert-Lab ($evidence.validTickets -eq $fixture.capacity) 'Paid ticket count differs from capacity'}
    'S04' {Assert-Lab ($evidence.validTickets -eq $fixture.work.Count*3) 'Session quota pairs must sell exactly three tickets per user'}
    'S05' {Assert-Lab ($evidence.skuA -eq 1000 -and $evidence.skuB -eq 0 -and $evidence.productPaid -eq 1) 'Multi-SKU rollback violated inventory or paid count';Assert-Lab ((Scalar "SELECT COUNT(*) FROM product_order WHERE status='CLOSED';") -eq 150) 'Failed multi-SKU orders must safely close'}
    'S06' {Assert-Lab ($evidence.productPaid -eq $fixture.work.Count -and (1500-$evidence.skuA-$evidence.skuB) -eq 2*$fixture.work.Count) 'Shared Product limit did not select exactly one order per user'}
    'S07' {Assert-Lab ($evidence.paidOrders -eq 2 -and $evidence.validTickets -eq 1 -and $evidence.refunds -eq 1) 'Repeated payment/refund produced extra effects'}
    'S09' {Assert-Lab ($evidence.validTickets -eq 0 -and $evidence.refunds -eq 100) 'Cancellation did not refund all 100 tickets';Assert-Lab ((Scalar "SELECT COUNT(*) FROM cancellation_batch WHERE status='COMPLETED';") -eq 1) 'Cancellation did not converge'}
}}finally{
    Save-Lab (Join-Path $OutputDirectory 'business-result.json') $evidence
    Lab-Reconcile $lab (Join-Path $OutputDirectory 'after')
}
$evidence
