param([Parameter(Mandatory)][string]$RunDirectory,[Parameter(Mandatory)][string]$OutputDirectory)
. (Join-Path $PSScriptRoot '../system/common.ps1')
$lab=Open-Lab $RunDirectory
$stop=Join-Path $OutputDirectory 'sampling.stop'
$metrics='{__name__=~"process_cpu_usage|system_cpu_usage|jvm_memory_used_bytes|jvm_gc_pause_seconds_(count|sum)|jvm_threads_live_threads|hikaricp_connections_(active|pending|max)|hikaricp_connections_acquire_seconds_(count|sum|max)|.*cache.*|outbox_pending|outbox_oldest_age_seconds|rabbitmq_queue_messages.*|cancellation_.*"}'
while(-not (Test-Path -LiteralPath $stop)){
    $record=@{at=[DateTimeOffset]::UtcNow.ToString('o')}
    try{$record.metrics=Lab-Prom $lab $metrics}catch{$record.metricsError=$_.Exception.GetType().Name}
    try{$record.mysql=Lab-Sql $lab "SHOW GLOBAL STATUS WHERE Variable_name IN ('Threads_running','Threads_connected','Innodb_row_lock_current_waits','Innodb_row_lock_waits','Innodb_row_lock_time','Questions'); SELECT waiting_pid,blocking_pid,locked_table,waiting_query,blocking_query FROM sys.innodb_lock_waits; SELECT 'CANCELLATION_PROGRESS',batch_id,status,scan_phase,next_cursor,total_items,succeeded_items,failed_items FROM cancellation_batch ORDER BY batch_id;" -Root}catch{$record.mysqlError=$_.Exception.GetType().Name}
    ($record|ConvertTo-Json -Depth 30 -Compress)|Add-Content (Join-Path $OutputDirectory 'samples.jsonl') -Encoding utf8
    Start-Sleep -Seconds 5
}
