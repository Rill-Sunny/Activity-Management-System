-- Phase 1 cross-domain checks. Run with orphans, reservation, refund and gift scripts
-- in one read-only REPEATABLE READ snapshot after all worker transactions finish.
SELECT 'DUPLICATE_VALID_SEAT' AS violation, CAST(seat_id AS CHAR) AS resource_id
FROM ticket WHERE status = 'VALID' GROUP BY seat_id HAVING COUNT(*) > 1;

SELECT 'PURCHASE_LIMIT' AS violation, CONCAT(p.user_id, ':', p.session_id) AS resource_id,
       s.max_tickets_per_user AS expected_value, p.valid_count AS actual_value
FROM user_session_purchase p
LEFT JOIN activity_session s ON s.activity_session_id = p.session_id
LEFT JOIN activity a ON a.activity_id = s.activity_id
WHERE s.activity_session_id IS NULL OR a.activity_id IS NULL OR a.type <> 'PAID'
   OR s.max_tickets_per_user IS NULL OR p.valid_count < 0 OR p.valid_count > s.max_tickets_per_user;

SELECT 'ZONE_SEAT_CAPACITY' AS violation, CAST(z.zone_id AS CHAR) AS resource_id,
       z.total_amount AS expected_value, COUNT(s.seat_id) AS actual_value
FROM session_zone z JOIN activity_session se ON se.activity_session_id = z.session_id
JOIN activity a ON a.activity_id = se.activity_id
LEFT JOIN session_seat s ON s.zone_id = z.zone_id
WHERE a.status <> 'DRAFT'
GROUP BY z.zone_id, z.total_amount HAVING COUNT(s.seat_id) <> z.total_amount;

SELECT 'ORDER_AMOUNT' AS violation, CAST(o.order_id AS CHAR) AS resource_id,
       o.total_amount AS expected_value, COALESCE(SUM(i.quantity * i.unit_price), 0) AS actual_value
FROM ticket_order o LEFT JOIN ticket_order_item i ON i.order_id = o.order_id
GROUP BY o.order_id, o.total_amount
HAVING COUNT(i.order_item_id) = 0 OR SUM(i.quantity * i.unit_price) <> o.total_amount;

-- Includes partial and full refunds: original ticket history still equals purchased quantity.
SELECT 'ISSUED_ITEM_QUANTITY' AS violation, CAST(i.order_item_id AS CHAR) AS resource_id,
       i.quantity AS expected_value, COUNT(t.ticket_id) AS actual_value
FROM ticket_order_item i JOIN ticket_order o ON o.order_id = i.order_id
LEFT JOIN ticket t ON t.order_item_id = i.order_item_id
WHERE o.status IN ('PAID', 'PARTIALLY_REFUNDED', 'REFUNDED')
GROUP BY i.order_item_id, i.quantity HAVING COUNT(t.ticket_id) <> i.quantity;

SELECT 'UNPAID_ORDER_TICKET' AS violation, CAST(t.ticket_id AS CHAR) AS resource_id
FROM ticket t JOIN ticket_order_item i ON i.order_item_id = t.order_item_id
JOIN ticket_order o ON o.order_id = i.order_id
WHERE o.status IN ('PENDING_PAYMENT', 'CLOSED');
