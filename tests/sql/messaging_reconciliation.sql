-- Diagnostic ledger, not a zero-row invariant. Compare these IDs with Rabbit ready/unacked/DLQ snapshots.
-- SENT only means publisher confirmation. It does not mean consumer completion.
SELECT e.event_id, e.event_type, e.aggregate_id, e.status AS outbox_status, e.attempts AS publish_attempts,
       e.failure_reason AS publish_failure, e.traceparent,
       cp.consumer_name, cp.processed_at, cf.status AS consumer_failure_status, cf.last_error,
       (SELECT COUNT(*) FROM notification n WHERE n.event_id=e.event_id) AS notification_count,
       (SELECT COUNT(*) FROM ticket_qualification_after_sale_task t WHERE t.source_event_id=e.event_id) AS task_count,
       (SELECT COUNT(*) FROM ticket_qualification_after_sale_task t WHERE t.source_event_id=e.event_id AND t.status='FAILED') AS failed_tasks
FROM outbox_event e
LEFT JOIN consumer_process cp ON cp.event_id=e.event_id
LEFT JOIN consumer_failure cf ON cf.event_id=e.event_id
ORDER BY e.created_at,e.event_id;
