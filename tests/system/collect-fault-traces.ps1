param([Parameter(Mandatory)][string]$RunDirectory)
. (Join-Path $PSScriptRoot 'common.ps1')
$lab=Open-Lab $RunDirectory
Assert-Lab ($lab.context.day -eq 28) 'Owned Day 28 traces required'
$output=Join-Path $lab.directory 'traces';[void][IO.Directory]::CreateDirectory($output)
$results=@()
foreach($file in Get-ChildItem $lab.directory -Recurse -Filter 'event-reconciliation.txt'){
    $text=Get-Content $file.FullName -Raw
    foreach($entry in [regex]::Matches($text,'(?m)^([a-f0-9-]{36})\t(PAYMENT_SUCCEEDED|REFUND_SUCCEEDED|SESSION_CANCELLED|ACTIVITY_CANCELLED)\tSENT\t([0-9]+)\t00-([a-f0-9]{32})-[a-f0-9]{16}-01')){
        $event=$entry.Groups[1].Value;$id=$entry.Groups[4].Value;$script:trace=$null
        Wait-Lab "recoverable trace $id" {
            try{
                $script:trace=Invoke-RestMethod ('http://127.0.0.1:'+$lab.settings.TEMPO_PORT+'/api/traces/'+$id) -TimeoutSec 10
                $script:names=@($script:trace.batches|ForEach-Object{$_.scopeSpans}|ForEach-Object{$_.spans}|ForEach-Object{$_.name})
                $script:names -contains 'outbox.publish' -and $script:names -contains 'mq.consume'
            }catch{$false}
        } 90
        Save-Lab (Join-Path $output ($event+'.json')) $script:trace
        $results+=@{scenario=$file.Directory.Name;eventId=$event;eventType=$entry.Groups[2].Value;attempts=[int]$entry.Groups[3].Value;traceId=$id;spans=$script:names;hasOutboxAndConsumer=$true}
    }
}
Assert-Lab ($results.Count -ge 5) 'Expected traces from multiple independent fault scenarios'
$httpIndex=@(Get-Content (Join-Path $lab.directory 'http-trace-index.jsonl')|ForEach-Object{$_|ConvertFrom-Json})
foreach($fault in Get-ChildItem $lab.directory -Directory -Filter 'Redis-*'){
    if(-not(Test-Path (Join-Path $fault.FullName 'result.json'))){continue}
    $timeline=Get-Content (Join-Path $fault.FullName 'timeline.json') -Raw|ConvertFrom-Json
    $stopped=[DateTimeOffset]($timeline|Where-Object step -eq 'Redis stopped').at
    $recovered=[DateTimeOffset]($timeline|Where-Object step -eq 'Redis recovered and cache converged').at
    $requests=@($httpIndex|Where-Object{[DateTimeOffset]$_.at -ge $stopped -and [DateTimeOffset]$_.at -le $recovered -and $_.status -lt 400 -and $_.method -eq 'POST'})
    Assert-Lab ($requests.Count -ge 3) 'Redis outage HTTP trace references are missing'
    foreach($request in $requests){
        $id=$request.traceparent.Split('-')[1];$script:trace=$null;$script:spans=@()
        Wait-Lab "Redis outage HTTP trace $id" {
            try{
                $script:trace=Invoke-RestMethod ('http://127.0.0.1:'+$lab.settings.TEMPO_PORT+'/api/traces/'+$id) -TimeoutSec 10
                $script:spans=@($script:trace.batches|ForEach-Object{$_.scopeSpans}|ForEach-Object{$_.spans})
                @($script:spans|Where-Object kind -eq 'SPAN_KIND_SERVER').Count -ge 1
            }catch{$false}
        } 90
        if($request.path -match '^/product-orders/.+/pay$'){Assert-Lab ($script:spans.name -contains 'product.payment') 'Product payment span missing during Redis outage'}
        Save-Lab (Join-Path $output ('http-'+$id+'.json')) $script:trace
        $results+=@{scenario=$fault.Name;eventType='HTTP';traceId=$id;path=$request.path;status=$request.status;spans=@($script:spans.name);hasHttpServer=$true}
    }
}
function Trace-Hex([string]$id){
    if($id -match '^[a-fA-F0-9]{32}$'){return $id.ToLowerInvariant()}
    [Convert]::ToHexString([Convert]::FromBase64String($id)).ToLowerInvariant()
}
$latest=Get-ChildItem $lab.directory -Directory -Filter 'F07-*'|Where-Object{Test-Path (Join-Path $_.FullName 'result.json')}|Sort-Object Name|Select-Object -Last 1
Assert-Lab ($null -ne $latest) 'Completed cancellation takeover evidence required'
$origin=@($results|Where-Object{$_.scenario -eq $latest.Name -and $_.eventType -in @('SESSION_CANCELLED','ACTIVITY_CANCELLED')})
$refundTraces=@($results|Where-Object{$_.scenario -eq $latest.Name -and $_.eventType -eq 'REFUND_SUCCEEDED'})
Assert-Lab ($origin.Count -eq 1 -and $refundTraces.Count -eq 3) 'Use full sampling for all three cancellation recovery refunds'
$links=@()
foreach($refund in $refundTraces){
    $trace=Get-Content (Join-Path $output ($refund.eventId+'.json')) -Raw|ConvertFrom-Json
    $spans=@($trace.batches|ForEach-Object{$_.scopeSpans}|ForEach-Object{$_.spans})
    $linked=@($spans|Where-Object{$_.name -eq 'cancellation.item.process'}|ForEach-Object{$_.links}|Where-Object{$_ -and (Trace-Hex $_.traceId) -eq $origin[0].traceId})
    Assert-Lab ($linked.Count -ge 1 -and $spans.name -contains 'ticket.refund') 'Recovery trace must link back to the durable cancellation event'
    $links+=@{refundEventId=$refund.eventId;recoveryTraceId=$refund.traceId;sourceTraceId=$origin[0].traceId;linkedTaskSpans=$linked.Count}
}
Save-Lab (Join-Path $output 'cancellation-recovery-links.json') $links
Save-Lab (Join-Path $output 'summary.json') $results
Write-Output ('RECOVERY_TRACES='+$results.Count)
