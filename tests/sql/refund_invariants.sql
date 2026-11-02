-- Read-only reconciliation after all payment/refund workers finish.
-- Every result set must be empty. No USE, DDL, DML or repair statements.
-- Run all queries in one consistent read snapshot when reconciling a live database.

-- 1. Each SOLD Seat has one VALID Ticket. Refunded historical Tickets may share a resold Seat.
SELECT 'SOLD_SEAT_TICKET' AS violation, CAST(s.seat_id AS CHAR) AS resource_id,
       1 AS expected_value, COUNT(t.ticket_id) AS actual_value
FROM session_seat s LEFT JOIN ticket t ON t.seat_id = s.seat_id AND t.status = 'VALID'
WHERE s.allocation_status = 'SOLD'
GROUP BY s.seat_id HAVING COUNT(t.ticket_id) <> 1;

SELECT 'VALID_TICKET_RELATION' AS violation, CAST(t.ticket_id AS CHAR) AS resource_id,
       1 AS expected_value, 0 AS actual_value
FROM ticket t
LEFT JOIN session_seat s ON s.seat_id = t.seat_id
LEFT JOIN session_zone z ON z.zone_id = s.zone_id
LEFT JOIN ticket_order_item i ON i.order_item_id = t.order_item_id
LEFT JOIN ticket_order o ON o.order_id = i.order_id
WHERE t.status = 'VALID' AND (
    s.seat_id IS NULL OR s.allocation_status <> 'SOLD' OR z.zone_id IS NULL OR z.zone_type <> 'PAID'
    OR i.order_item_id IS NULL OR i.zone_id <> s.zone_id OR o.order_id IS NULL
    OR z.session_id <> o.session_id OR o.status NOT IN ('PAID', 'PARTIALLY_REFUNDED')
    OR t.paid_price <> i.unit_price OR t.refunded_at IS NOT NULL
);

-- 2. Refunded Tickets retain timestamps and exactly one successful refund result.
SELECT 'REFUNDED_TICKET_RESULT' AS violation, CAST(t.ticket_id AS CHAR) AS resource_id,
       1 AS expected_value, COUNT(ri.refund_item_id) AS actual_value
FROM ticket t LEFT JOIN ticket_refund_item ri ON ri.ticket_id = t.ticket_id AND ri.status = 'SUCCEEDED'
WHERE t.status = 'REFUNDED'
GROUP BY t.ticket_id, t.refunded_at
HAVING t.refunded_at IS NULL OR COUNT(ri.refund_item_id) <> 1;

-- 3. Check mismatched counters and missing counter rows, including fully refunded orders.
WITH valid_counts AS (
    SELECT o.buyer_id AS user_id, o.session_id, COUNT(*) AS ticket_count
    FROM ticket t JOIN ticket_order_item i ON i.order_item_id = t.order_item_id
    JOIN ticket_order o ON o.order_id = i.order_id
    WHERE t.status = 'VALID' GROUP BY o.buyer_id, o.session_id
)
SELECT 'PURCHASE_COUNT' AS violation, CONCAT(p.user_id, ':', p.session_id) AS resource_id,
       COALESCE(v.ticket_count, 0) AS expected_value, p.valid_count AS actual_value
FROM user_session_purchase p LEFT JOIN valid_counts v ON v.user_id = p.user_id AND v.session_id = p.session_id
WHERE p.valid_count <> COALESCE(v.ticket_count, 0)
UNION ALL
SELECT 'MISSING_PURCHASE_COUNTER', CONCAT(v.user_id, ':', v.session_id), v.ticket_count, 0
FROM valid_counts v LEFT JOIN user_session_purchase p ON p.user_id = v.user_id AND p.session_id = v.session_id
WHERE p.user_id IS NULL;

-- 4. PAID inventory equals unsold stock plus physical SOLD Seats.
SELECT 'ZONE_INVENTORY' AS violation, CAST(z.zone_id AS CHAR) AS resource_id,
       z.total_amount AS expected_value, z.remains_amount + COALESCE(s.sold, 0) AS actual_value
FROM session_zone z
LEFT JOIN (
    SELECT zone_id, COUNT(*) AS sold FROM session_seat WHERE allocation_status = 'SOLD' GROUP BY zone_id
) s ON s.zone_id = z.zone_id
WHERE z.zone_type = 'PAID' AND z.remains_amount + COALESCE(s.sold, 0) <> z.total_amount;

-- 5. No request total column is stored. Reconcile the per-request SUM against the frozen quote formula.
SELECT 'REFUND_REQUEST_AMOUNT' AS violation, CAST(r.refund_id AS CHAR) AS resource_id,
       COALESCE(SUM(ROUND(ri.original_price * (1 - ri.fee_rate), 2)), 0) AS expected_value,
       COALESCE(SUM(ri.refund_amount), 0) AS actual_value
FROM ticket_refund_request r LEFT JOIN ticket_refund_item ri ON ri.refund_id = r.refund_id
GROUP BY r.refund_id
HAVING COUNT(ri.refund_item_id) = 0
    OR SUM(ri.refund_amount) <> SUM(ROUND(ri.original_price * (1 - ri.fee_rate), 2));

SELECT 'REFUND_ITEM_RELATION' AS violation, CAST(ri.refund_item_id AS CHAR) AS resource_id,
       1 AS expected_value, 0 AS actual_value
FROM ticket_refund_item ri
LEFT JOIN ticket_refund_request r ON r.refund_id = ri.refund_id
LEFT JOIN ticket t ON t.ticket_id = ri.ticket_id
LEFT JOIN ticket_order_item i ON i.order_item_id = t.order_item_id
LEFT JOIN ticket_order o ON o.order_id = i.order_id
WHERE r.refund_id IS NULL OR t.ticket_id IS NULL OR i.order_item_id IS NULL OR o.order_id IS NULL
   OR r.order_id <> o.order_id OR r.buyer_id <> o.buyer_id OR ri.original_price <> t.paid_price
   OR ri.refund_amount <> ROUND(ri.original_price * (1 - ri.fee_rate), 2);

-- 6. An atomic request cannot have partially completed Items or successful Items without refunded Tickets.
SELECT 'REFUND_STATE' AS violation, CAST(r.refund_id AS CHAR) AS resource_id,
       COUNT(ri.refund_item_id) AS expected_value,
       SUM(CASE WHEN ri.status = r.status THEN 1 ELSE 0 END) AS actual_value
FROM ticket_refund_request r LEFT JOIN ticket_refund_item ri ON ri.refund_id = r.refund_id
GROUP BY r.refund_id, r.status
HAVING COUNT(ri.refund_item_id) = 0 OR SUM(CASE WHEN ri.status = r.status THEN 1 ELSE 0 END) <> COUNT(ri.refund_item_id);

SELECT 'SUCCEEDED_REFUND_RESULT' AS violation, CAST(ri.refund_item_id AS CHAR) AS resource_id,
       1 AS expected_value, 0 AS actual_value
FROM ticket_refund_item ri JOIN ticket_refund_request r ON r.refund_id = ri.refund_id
LEFT JOIN ticket t ON t.ticket_id = ri.ticket_id
WHERE ri.status = 'SUCCEEDED' AND (
    t.ticket_id IS NULL OR t.status <> 'REFUNDED' OR t.refunded_at IS NULL
    OR r.status <> 'SUCCEEDED' OR r.processed_at IS NULL OR ri.processed_at IS NULL
    OR r.result IS NULL OR ri.result IS NULL
    OR r.result <> 'SIMULATED_REFUND_SUCCEEDED' OR ri.result <> r.result
    OR ri.processed_at <> r.processed_at OR t.refunded_at <> r.processed_at
);

-- 7. Summary derives from remaining VALID Tickets, not refund request count.
SELECT 'ORDER_REFUND_SUMMARY' AS violation, CAST(o.order_id AS CHAR) AS resource_id,
       CASE WHEN COALESCE(t.valid_count, 0) = 0 THEN 0 ELSE 1 END AS expected_value,
       CASE WHEN o.status = 'REFUNDED' THEN 0 ELSE 1 END AS actual_value
FROM ticket_order o
LEFT JOIN (
    SELECT i.order_id, SUM(t.status = 'VALID') AS valid_count, SUM(t.status = 'REFUNDED') AS refunded_count,
           COUNT(*) AS ticket_count
    FROM ticket t JOIN ticket_order_item i ON i.order_item_id = t.order_item_id GROUP BY i.order_id
) t ON t.order_id = o.order_id
WHERE o.status IN ('PAID', 'PARTIALLY_REFUNDED', 'REFUNDED') AND (
    COALESCE(t.ticket_count, 0) = 0
    OR (o.status = 'PAID' AND COALESCE(t.refunded_count, 0) > 0)
    OR (o.status = 'PARTIALLY_REFUNDED' AND (COALESCE(t.valid_count, 0) = 0 OR COALESCE(t.refunded_count, 0) = 0))
    OR (o.status = 'REFUNDED' AND COALESCE(t.valid_count, 0) <> 0)
);
