-- Read-only reconciliation. Every result set must be empty after all payment transactions finish.
-- Use the current test database. This script deliberately contains no USE, DDL, DML or repair logic.

-- 1. Public paid inventory must match physical sold seats.
SELECT 'ZONE_INVENTORY' AS violation, CAST(z.zone_id AS CHAR) AS resource_id,
       z.total_amount AS expected_value, z.remains_amount + COALESCE(s.sold, 0) AS actual_value
FROM session_zone z
LEFT JOIN (
    SELECT zone_id, COUNT(*) AS sold FROM session_seat WHERE allocation_status = 'SOLD' GROUP BY zone_id
) s ON s.zone_id = z.zone_id
WHERE z.zone_type = 'PAID' AND z.remains_amount + COALESCE(s.sold, 0) <> z.total_amount;

-- 2. Each SOLD seat has exactly one VALID Ticket. VALID Tickets have a consistent paid ownership chain.
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
LEFT JOIN activity_session se ON se.activity_session_id = o.session_id
WHERE t.status = 'VALID' AND (
    s.seat_id IS NULL OR s.allocation_status <> 'SOLD' OR z.zone_id IS NULL OR z.zone_type <> 'PAID'
    OR i.order_item_id IS NULL OR i.zone_id <> s.zone_id OR o.order_id IS NULL
    OR z.session_id <> o.session_id OR se.activity_session_id IS NULL
    OR o.status NOT IN ('PAID', 'PARTIALLY_REFUNDED') OR t.paid_price <> i.unit_price
);

-- 3. Check both mismatched counters and missing counter rows.
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

-- 4. Check total and per-item quantities, so an equal total cannot hide wrongly distributed Tickets.
SELECT 'PAID_ORDER_TICKET_COUNT' AS violation, CAST(o.order_id AS CHAR) AS resource_id,
       COALESCE(i.quantity, 0) AS expected_value, COALESCE(t.ticket_count, 0) AS actual_value
FROM ticket_order o
LEFT JOIN (SELECT order_id, SUM(quantity) AS quantity FROM ticket_order_item GROUP BY order_id) i ON i.order_id = o.order_id
LEFT JOIN (
    SELECT i.order_id, COUNT(*) AS ticket_count FROM ticket t
    JOIN ticket_order_item i ON i.order_item_id = t.order_item_id GROUP BY i.order_id
) t ON t.order_id = o.order_id
WHERE o.status = 'PAID' AND (COALESCE(i.quantity, 0) = 0 OR COALESCE(i.quantity, 0) <> COALESCE(t.ticket_count, 0));

SELECT 'PAID_ITEM_TICKET_COUNT' AS violation, CAST(i.order_item_id AS CHAR) AS resource_id,
       i.quantity AS expected_value, COUNT(t.ticket_id) AS actual_value
FROM ticket_order_item i JOIN ticket_order o ON o.order_id = i.order_id
LEFT JOIN ticket t ON t.order_item_id = i.order_item_id
WHERE o.status = 'PAID'
GROUP BY i.order_item_id, i.quantity HAVING COUNT(t.ticket_id) <> i.quantity;
