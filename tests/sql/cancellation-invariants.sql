-- Zero rows are expected. In-flight batches are valid if durable progress and recovery remain available.
SELECT 'CANCELLATION_EVENT_RELATION' violation,CAST(e.event_id AS CHAR) resource_id
FROM cancellation_event e LEFT JOIN activity a ON a.activity_id=e.activity_id LEFT JOIN app_user u ON u.id=e.publisher_id
LEFT JOIN cancellation_batch b ON b.event_id=e.event_id
WHERE a.activity_id IS NULL OR u.id IS NULL OR b.batch_id IS NULL OR a.publisher_id<>e.publisher_id
 OR (e.cancel_type='ACTIVITY' AND (e.target_id<>e.activity_id OR a.status<>'CANCELLED'));

SELECT 'CANCELLATION_SCOPE' violation,CONCAT(c.event_id,':',c.session_id) resource_id
FROM cancellation_scope c LEFT JOIN cancellation_event e ON e.event_id=c.event_id
LEFT JOIN activity_session s ON s.activity_session_id=c.session_id
WHERE e.event_id IS NULL OR s.activity_session_id IS NULL OR s.activity_id<>e.activity_id OR s.status<>'CANCELLED'
 OR (e.cancel_type='SESSION' AND e.target_id<>c.session_id) OR s.end_time<=e.cancelled_at;

SELECT 'CANCELLATION_MISSING_SCOPE' violation,CAST(e.event_id AS CHAR) resource_id
FROM cancellation_event e LEFT JOIN cancellation_scope c ON c.event_id=e.event_id AND c.session_id=e.target_id
WHERE e.cancel_type='SESSION' AND c.session_id IS NULL;

SELECT 'CANCELLATION_OUTBOX' violation,CAST(e.event_id AS CHAR) resource_id
FROM cancellation_event e LEFT JOIN outbox_event o ON o.aggregate_id=e.event_id AND o.event_type=CONCAT(e.cancel_type,'_CANCELLED')
GROUP BY e.event_id HAVING COUNT(o.event_id)<>1;

SELECT 'CANCELLATION_ITEM_SCOPE' violation,CAST(i.item_id AS CHAR) resource_id
FROM cancellation_batch_item i LEFT JOIN cancellation_batch b ON b.batch_id=i.batch_id
LEFT JOIN cancellation_scope c ON c.event_id=b.event_id AND c.session_id=i.session_id
WHERE b.batch_id IS NULL OR c.session_id IS NULL;

SELECT 'CANCELLATION_TARGET_ORPHAN' violation,CAST(i.item_id AS CHAR) resource_id
FROM cancellation_batch_item i
LEFT JOIN ticket_order o ON i.target_type='ORDER' AND o.order_id=i.target_id
LEFT JOIN ticket t ON i.target_type='TICKET' AND t.ticket_id=i.target_id
LEFT JOIN ticket_order_item ti ON ti.order_item_id=t.order_item_id
LEFT JOIN ticket_order ot ON ot.order_id=ti.order_id
LEFT JOIN free_reservation r ON i.target_type='RESERVATION' AND r.reservation_id=i.target_id
LEFT JOIN gift_inventory_operation_item g ON i.target_type='GIFT' AND g.operation_item_id=i.target_id
LEFT JOIN gift_inventory_operation go ON go.operation_id=g.operation_id
WHERE (i.target_type='ORDER' AND (o.order_id IS NULL OR o.session_id<>i.session_id))
 OR (i.target_type='TICKET' AND (t.ticket_id IS NULL OR ot.order_id IS NULL OR ot.session_id<>i.session_id))
 OR (i.target_type='RESERVATION' AND (r.reservation_id IS NULL OR r.session_id<>i.session_id))
 OR (i.target_type='GIFT' AND (g.operation_item_id IS NULL OR go.operation_id IS NULL OR go.session_id<>i.session_id));

SELECT 'CANCELLATION_BATCH_ORPHAN' violation,CAST(b.batch_id AS CHAR) resource_id
FROM cancellation_batch b LEFT JOIN cancellation_event e ON e.event_id=b.event_id WHERE e.event_id IS NULL;

SELECT 'CANCELLATION_OUTBOX_ORPHAN' violation,o.event_id resource_id
FROM outbox_event o LEFT JOIN cancellation_event e ON e.event_id=o.aggregate_id
WHERE o.event_type IN ('SESSION_CANCELLED','ACTIVITY_CANCELLED') AND (e.event_id IS NULL OR o.event_type<>CONCAT(e.cancel_type,'_CANCELLED'));

SELECT 'CANCELLATION_CONSUMER_WITHOUT_PROGRESS' violation,p.event_id resource_id
FROM consumer_process p LEFT JOIN outbox_event o ON o.event_id=p.event_id
LEFT JOIN cancellation_batch b ON b.event_id=o.aggregate_id
WHERE p.consumer_name='cancellation-generation-v1' AND (o.event_type NOT IN ('SESSION_CANCELLED','ACTIVITY_CANCELLED')
 OR b.batch_id IS NULL OR (b.scan_phase=0 AND b.next_cursor=0));

SELECT 'CANCELLATION_BATCH_COUNTS' violation,CAST(b.batch_id AS CHAR) resource_id
FROM cancellation_batch b LEFT JOIN cancellation_batch_item i ON i.batch_id=b.batch_id
GROUP BY b.batch_id,b.total_items,b.succeeded_items,b.failed_items,b.status,b.scan_phase
HAVING b.total_items<>COUNT(i.item_id) OR b.succeeded_items<>COALESCE(SUM(i.status='SUCCEEDED'),0)
 OR b.failed_items<>COALESCE(SUM(i.status='FAILED'),0)
 OR (b.status='COMPLETED' AND (b.scan_phase<>4 OR b.total_items<>b.succeeded_items));

SELECT 'CANCELLATION_ILLEGAL_TRADE' violation,CAST(o.order_id AS CHAR) resource_id
FROM ticket_order o JOIN cancellation_scope c ON c.session_id=o.session_id JOIN cancellation_event e ON e.event_id=c.event_id
WHERE o.created_at>e.cancelled_at OR o.paid_at>e.cancelled_at;

SELECT 'CANCELLATION_UNTRACKED_TICKET' violation,CAST(t.ticket_id AS CHAR) resource_id
FROM ticket t JOIN ticket_order_item oi ON oi.order_item_id=t.order_item_id JOIN ticket_order o ON o.order_id=oi.order_id
JOIN cancellation_scope c ON c.session_id=o.session_id JOIN cancellation_batch b ON b.event_id=c.event_id
LEFT JOIN cancellation_batch_item i ON i.batch_id=b.batch_id AND i.target_type='TICKET' AND i.target_id=t.ticket_id
WHERE t.status='VALID' AND ((b.scan_phase>1 AND i.item_id IS NULL) OR b.status='COMPLETED' OR i.status='SUCCEEDED');

SELECT 'CANCELLATION_RESOURCE_INCOMPLETE' violation,CAST(i.item_id AS CHAR) resource_id
FROM cancellation_batch_item i
LEFT JOIN ticket_order o ON i.target_type='ORDER' AND o.order_id=i.target_id
LEFT JOIN free_reservation r ON i.target_type='RESERVATION' AND r.reservation_id=i.target_id
LEFT JOIN gift_inventory_operation_item g ON i.target_type='GIFT' AND g.operation_item_id=i.target_id
WHERE i.status='SUCCEEDED' AND ((i.target_type='ORDER' AND (o.order_id IS NULL OR o.session_id<>i.session_id OR o.status='PENDING_PAYMENT'))
 OR (i.target_type='RESERVATION' AND (r.reservation_id IS NULL OR r.session_id<>i.session_id OR r.status='ACTIVE'))
 OR (i.target_type='GIFT' AND (g.operation_item_id IS NULL OR g.cancelled_at IS NULL)));

SELECT 'CANCELLATION_REFUND_POLICY' violation,CAST(r.refund_id AS CHAR) resource_id
FROM ticket_refund_request r JOIN ticket_refund_item ri ON ri.refund_id=r.refund_id
LEFT JOIN cancellation_event e ON e.event_id=r.source_event_id
LEFT JOIN ticket_order o ON o.order_id=r.order_id
LEFT JOIN cancellation_scope c ON c.event_id=e.event_id AND c.session_id=o.session_id
LEFT JOIN cancellation_batch b ON b.event_id=e.event_id
LEFT JOIN cancellation_batch_item i ON i.batch_id=b.batch_id AND i.target_type='TICKET' AND i.target_id=ri.ticket_id AND i.refund_id=r.refund_id
WHERE r.refund_policy='CANCELLATION_REFUND' AND (e.event_id IS NULL OR c.session_id IS NULL OR i.item_id IS NULL
 OR r.requested_at<e.cancelled_at OR ri.fee_rate<>0 OR ri.refund_amount<>ri.original_price OR r.status<>'SUCCEEDED' OR i.status<>'SUCCEEDED');

SELECT 'CANCELLATION_GIFT_EFFECT' violation,CAST(g.operation_item_id AS CHAR) resource_id
FROM gift_inventory_operation_item g JOIN gift_inventory_operation o ON o.operation_id=g.operation_id
JOIN session_seat s ON s.seat_id=g.seat_id LEFT JOIN cancellation_scope c ON c.event_id=g.cancellation_event_id AND c.session_id=o.session_id
WHERE (g.cancelled_at IS NOT NULL AND (c.session_id IS NULL OR s.allocation_status<>'GIFT_CANCELLED'))
 OR (s.allocation_status='GIFT_CANCELLED' AND g.cancelled_at IS NULL);
