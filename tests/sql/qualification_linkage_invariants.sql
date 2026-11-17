-- Qualification tasks must remain aligned with the frozen item qualification snapshot.
SELECT 'qualification_task_snapshot_mismatch' AS violation, t.task_id AS resource_id
FROM ticket_qualification_after_sale_task t
JOIN product_order_item i ON i.order_item_id=t.product_order_item_id
WHERE i.qualification_type_snapshot <> 'TICKET_REQUIRED'
   OR i.qualification_scope_snapshot <> t.qualification_scope
   OR (t.qualification_scope='SESSION' AND i.qualification_session_id_snapshot IS NULL);

-- A task marked complete must have no remaining refundable quantity or reservation.
SELECT 'succeeded_qualification_task_has_unprocessed_quantity' AS violation, t.task_id AS resource_id
FROM ticket_qualification_after_sale_task t
JOIN product_order_item i ON i.order_item_id=t.product_order_item_id
WHERE t.status='SUCCEEDED'
  AND (i.refunded_quantity < i.quantity OR i.refund_pending_quantity <> 0);

-- A task may wait for return while the item is shipped or after its receipt was recorded.
SELECT 'waiting_return_task_has_invalid_fulfillment_state' AS violation, t.task_id AS resource_id
FROM ticket_qualification_after_sale_task t
JOIN product_order_item i ON i.order_item_id=t.product_order_item_id
WHERE t.status='WAITING_RETURN' AND i.fulfillment_status NOT IN ('SHIPPED','RECEIVED');

-- A failed task must retain a human-readable failure reason and a bounded attempt count.
SELECT 'failed_qualification_task_missing_failure' AS violation, t.task_id AS resource_id
FROM ticket_qualification_after_sale_task t
WHERE t.status='FAILED' AND (t.attempt_count < 1 OR t.attempt_count > 5
    OR t.failure_reason IS NULL OR CHAR_LENGTH(TRIM(t.failure_reason))=0);
