-- ALLOCATE-only GIFT reconciliation. Every result set must be empty after workers finish.
-- No receivers or paid Tickets exist for these resources. This script never repairs data.
SELECT 'PAID_STOCK' AS violation, CAST(z.zone_id AS CHAR) AS resource_id,
       z.total_amount AS expected_value, z.remains_amount + COALESCE(s.sold, 0) AS actual_value
FROM session_zone z
LEFT JOIN (SELECT zone_id, COUNT(*) AS sold FROM session_seat WHERE allocation_status = 'SOLD' GROUP BY zone_id) s
  ON s.zone_id = z.zone_id
WHERE z.zone_type = 'PAID' AND z.remains_amount + COALESCE(s.sold, 0) <> z.total_amount;

SELECT 'SESSION_CAPACITY' AS violation, CAST(s.activity_session_id AS CHAR) AS resource_id,
       s.capacity AS expected_value,
       SUM(CASE WHEN z.zone_type = 'PAID' THEN z.total_amount ELSE 0 END)
       + SUM(CASE WHEN z.zone_type = 'GIFT' THEN z.total_amount ELSE 0 END) AS actual_value
FROM activity_session s LEFT JOIN session_zone z ON z.session_id = s.activity_session_id
GROUP BY s.activity_session_id, s.capacity
HAVING SUM(CASE WHEN z.zone_type = 'PAID' THEN z.total_amount ELSE 0 END)
       + SUM(CASE WHEN z.zone_type = 'GIFT' THEN z.total_amount ELSE 0 END) > s.capacity;

SELECT 'GIFT_STOCK' AS violation, CAST(z.zone_id AS CHAR) AS resource_id
FROM session_zone z
LEFT JOIN (SELECT zone_id, COUNT(*) AS allocated FROM session_seat WHERE allocation_status IN ('GIFT_ALLOCATED','GIFT_CANCELLED') GROUP BY zone_id) s
  ON s.zone_id = z.zone_id
WHERE z.zone_type = 'GIFT' AND z.remains_amount + COALESCE(s.allocated, 0) <> z.total_amount;

SELECT 'SEAT_POOL' AS violation, CAST(s.seat_id AS CHAR) AS resource_id
FROM session_seat s JOIN session_zone z ON z.zone_id = s.zone_id
WHERE (z.zone_type = 'GIFT' AND s.allocation_status = 'SOLD') OR (z.zone_type = 'PAID' AND s.allocation_status IN ('GIFT_ALLOCATED','GIFT_CANCELLED'));

SELECT 'GIFT_OPERATION_ITEMS' AS violation, CAST(o.operation_id AS CHAR) AS resource_id
FROM gift_inventory_operation o LEFT JOIN gift_inventory_operation_item i ON i.operation_id = o.operation_id
GROUP BY o.operation_id, o.quantity HAVING COUNT(i.operation_item_id) <> o.quantity;

SELECT 'GIFT_ITEM_RELATION' AS violation, CAST(i.operation_item_id AS CHAR) AS resource_id
FROM gift_inventory_operation_item i
LEFT JOIN gift_inventory_operation o ON o.operation_id = i.operation_id
LEFT JOIN session_seat s ON s.seat_id = i.seat_id
LEFT JOIN session_zone z ON z.zone_id = s.zone_id
WHERE o.operation_id IS NULL OR s.seat_id IS NULL OR z.zone_id IS NULL
   OR z.zone_type <> 'GIFT' OR o.zone_id <> s.zone_id OR o.session_id <> z.session_id
   OR s.allocation_status NOT IN ('GIFT_ALLOCATED','GIFT_CANCELLED');

SELECT 'GIFT_SEAT_HISTORY' AS violation, CAST(s.seat_id AS CHAR) AS resource_id
FROM session_seat s LEFT JOIN gift_inventory_operation_item i ON i.seat_id = s.seat_id
WHERE s.allocation_status IN ('GIFT_ALLOCATED','GIFT_CANCELLED')
GROUP BY s.seat_id HAVING COUNT(i.operation_item_id) <> 1;

SELECT 'GIFT_PAID_TICKET' AS violation, CAST(t.ticket_id AS CHAR) AS resource_id
FROM ticket t JOIN session_seat s ON s.seat_id = t.seat_id JOIN session_zone z ON z.zone_id = s.zone_id
WHERE z.zone_type = 'GIFT';
