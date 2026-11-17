-- Payment/expiry aggregate IDs refer to orders; refund/qualification IDs refer to refund requests.
SELECT 'OUTBOX_WITHOUT_SOURCE' AS violation, oe.event_id AS resource_id, oe.aggregate_id AS expected_value
FROM outbox_event oe
LEFT JOIN ticket_order o ON o.order_id = oe.aggregate_id
LEFT JOIN ticket_refund_request r ON r.refund_id = oe.aggregate_id
WHERE (oe.event_type IN ('ORDER_EXPIRED','PAYMENT_SUCCEEDED') AND o.order_id IS NULL)
   OR (oe.event_type IN ('REFUND_SUCCEEDED','TICKET_QUALIFICATION_CHANGED') AND r.refund_id IS NULL);

SELECT 'CONSUMER_PROCESS_WITHOUT_OUTBOX' AS violation,
       CONCAT(cp.consumer_name, ':', cp.event_id) AS resource_id
FROM consumer_process cp LEFT JOIN outbox_event oe ON oe.event_id = cp.event_id
WHERE oe.event_id IS NULL;

SELECT 'CONSUMER_FAILURE_WITHOUT_OUTBOX' AS violation,
       CONCAT(cf.consumer_name, ':', cf.event_id) AS resource_id
FROM consumer_failure cf LEFT JOIN outbox_event oe ON oe.event_id = cf.event_id
WHERE oe.event_id IS NULL;

-- Notification creation and its consumer idempotency marker must commit together.
SELECT 'NOTIFICATION_WITHOUT_PROCESS_MARKER' AS violation,
       CAST(n.notification_id AS CHAR) AS resource_id
FROM notification n LEFT JOIN consumer_process cp ON cp.event_id = n.event_id
    AND cp.consumer_name IN ('payment-notification-v1', 'refund-notification-v1', 'session-reschedule-notification-v1')
WHERE cp.event_id IS NULL;

SELECT 'DUPLICATE_NOTIFICATION_BUSINESS_KEY' AS violation,
       CONCAT(user_id, ':', event_id) AS resource_id, COUNT(*) AS actual_value
FROM notification GROUP BY user_id, event_id HAVING COUNT(*) > 1;

SELECT 'OUTBOX_STATE_SHAPE' AS violation, event_id AS resource_id, status AS actual_value
FROM outbox_event
WHERE (status = 'CLAIMED' AND (lease_until IS NULL OR lease_token IS NULL))
   OR (status <> 'CLAIMED' AND (lease_until IS NOT NULL OR lease_token IS NOT NULL))
   OR (status = 'SENT' AND published_at IS NULL)
   OR (status <> 'SENT' AND published_at IS NOT NULL);

