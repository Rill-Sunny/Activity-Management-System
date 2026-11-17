-- Each result set is a violation list and must be empty in normal committed state.
SELECT 'RESCHEDULE_SNAPSHOT_RELATION' AS violation, CONCAT(a.event_id,':',a.ticket_id) AS resource_id
FROM reschedule_affected_ticket a
LEFT JOIN session_reschedule_event e ON e.event_id=a.event_id
LEFT JOIN ticket t ON t.ticket_id=a.ticket_id
LEFT JOIN ticket_order_item i ON i.order_item_id=t.order_item_id
LEFT JOIN ticket_order o ON o.order_id=i.order_id
LEFT JOIN reschedule_response r ON r.event_id=a.event_id AND r.ticket_id=a.ticket_id
WHERE e.event_id IS NULL OR t.ticket_id IS NULL OR i.order_item_id IS NULL OR o.order_id IS NULL OR r.event_id IS NULL
 OR a.session_id<>e.session_id OR a.session_id<>o.session_id OR a.order_id<>o.order_id OR a.buyer_id<>o.buyer_id
 OR a.seat_id<>t.seat_id OR a.original_paid_amount<>t.paid_price OR t.issued_at>e.announcement_time;

SELECT 'RESCHEDULE_EVENT_RELATION' AS violation, CAST(e.event_id AS CHAR) AS resource_id
FROM session_reschedule_event e
LEFT JOIN activity_session s ON s.activity_session_id=e.session_id
LEFT JOIN activity a ON a.activity_id=s.activity_id
LEFT JOIN app_user u ON u.id=e.operator_id
WHERE s.activity_session_id IS NULL OR a.activity_id IS NULL OR u.id IS NULL OR e.operator_id<>a.publisher_id
 OR e.special_refund_deadline<>LEAST(TIMESTAMPADD(DAY,7,e.announcement_time),e.new_start_time)
 OR e.new_start_time<a.close_time;

SELECT 'RESCHEDULE_RESPONSE_ORPHAN' AS violation, CONCAT(r.event_id,':',r.ticket_id) AS resource_id
FROM reschedule_response r LEFT JOIN reschedule_affected_ticket a USING(event_id,ticket_id)
WHERE a.event_id IS NULL;

SELECT 'RESCHEDULE_RESPONSE_TIME' AS violation, CONCAT(r.event_id,':',r.ticket_id) AS resource_id
FROM reschedule_response r JOIN session_reschedule_event e ON e.event_id=r.event_id
WHERE (r.status='USER_CONFIRMED' AND (r.responded_at<e.announcement_time OR r.responded_at>=e.special_refund_deadline))
 OR (r.status='AUTO_RETAINED' AND r.auto_retained_at<e.special_refund_deadline);

SELECT 'RESCHEDULE_REFUND_POLICY' AS violation, CAST(r.refund_id AS CHAR) AS resource_id
FROM ticket_refund_request r JOIN ticket_refund_item i ON i.refund_id=r.refund_id
LEFT JOIN session_reschedule_event e ON e.event_id=r.source_event_id
LEFT JOIN reschedule_affected_ticket a ON a.event_id=r.source_event_id AND a.ticket_id=i.ticket_id
LEFT JOIN reschedule_refund_link l ON l.refund_id=r.refund_id
LEFT JOIN reschedule_refund_submission b ON b.submission_id=l.submission_id
WHERE r.refund_policy='SESSION_RESCHEDULE_REFUND' AND (e.event_id IS NULL OR a.event_id IS NULL OR b.submission_id IS NULL
 OR r.accepted_at IS NULL OR r.accepted_at<>r.requested_at OR r.accepted_at<e.announcement_time
 OR r.accepted_at>=e.special_refund_deadline OR i.fee_rate<>0 OR i.refund_amount<>a.original_paid_amount
 OR a.buyer_id<>r.buyer_id OR a.order_id<>r.order_id OR b.buyer_id<>r.buyer_id OR b.event_id<>e.event_id
 OR b.accepted_at<>r.accepted_at);

SELECT 'RESCHEDULE_SUBMISSION_ORPHAN' AS violation, CAST(b.submission_id AS CHAR) AS resource_id
FROM reschedule_refund_submission b LEFT JOIN reschedule_refund_link l ON l.submission_id=b.submission_id
LEFT JOIN ticket_refund_request r ON r.refund_id=l.refund_id
WHERE r.refund_id IS NULL;

SELECT 'RESCHEDULE_OUTBOX_MISSING' AS violation, CAST(e.event_id AS CHAR) AS resource_id
FROM session_reschedule_event e LEFT JOIN outbox_event o
ON o.aggregate_id=e.event_id AND o.event_type IN ('SESSION_RESCHEDULED','RESCHEDULE_WINDOW_CLOSED')
GROUP BY e.event_id HAVING COUNT(*)<>2 OR COUNT(DISTINCT o.event_type)<>2;

WITH history AS (
 SELECT event_id,session_id,old_start_time,old_end_time,
 LAG(new_start_time) OVER(PARTITION BY session_id ORDER BY event_id) AS previous_start,
 LAG(new_end_time) OVER(PARTITION BY session_id ORDER BY event_id) AS previous_end
 FROM session_reschedule_event
)
SELECT 'RESCHEDULE_HISTORY_CHAIN' AS violation, CAST(event_id AS CHAR) AS resource_id
FROM history WHERE previous_start IS NOT NULL AND (old_start_time<>previous_start OR old_end_time<>previous_end);

SELECT 'RESCHEDULE_OUTBOX_ORPHAN' AS violation, o.event_id AS resource_id
FROM outbox_event o LEFT JOIN session_reschedule_event e ON e.event_id=o.aggregate_id
WHERE o.event_type IN ('SESSION_RESCHEDULED','RESCHEDULE_WINDOW_CLOSED') AND e.event_id IS NULL;
