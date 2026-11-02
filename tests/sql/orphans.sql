-- Logical references, including retained history. Zero physical FK requires explicit audits.
-- Every result set must be empty. No DDL, DML or repair statements.
SELECT 'ACTIVITY_PUBLISHER' AS violation, CAST(a.activity_id AS CHAR) AS resource_id
FROM activity a LEFT JOIN app_user u ON u.id = a.publisher_id WHERE u.id IS NULL;
SELECT 'SESSION_ACTIVITY' AS violation, CAST(s.activity_session_id AS CHAR) AS resource_id
FROM activity_session s LEFT JOIN activity a ON a.activity_id = s.activity_id WHERE a.activity_id IS NULL;
SELECT 'ZONE_SESSION' AS violation, CAST(z.zone_id AS CHAR) AS resource_id
FROM session_zone z LEFT JOIN activity_session s ON s.activity_session_id = z.session_id WHERE s.activity_session_id IS NULL;
SELECT 'SEAT_ZONE' AS violation, CAST(s.seat_id AS CHAR) AS resource_id
FROM session_seat s LEFT JOIN session_zone z ON z.zone_id = s.zone_id WHERE z.zone_id IS NULL;
SELECT 'ORDER_USER_SESSION' AS violation, CAST(o.order_id AS CHAR) AS resource_id
FROM ticket_order o LEFT JOIN app_user u ON u.id = o.buyer_id
LEFT JOIN activity_session s ON s.activity_session_id = o.session_id WHERE u.id IS NULL OR s.activity_session_id IS NULL;
SELECT 'ITEM_ORDER_ZONE_SEAT' AS violation, CAST(i.order_item_id AS CHAR) AS resource_id
FROM ticket_order_item i LEFT JOIN ticket_order o ON o.order_id = i.order_id
LEFT JOIN session_zone z ON z.zone_id = i.zone_id LEFT JOIN session_seat s ON s.seat_id = i.requested_seat_id
WHERE o.order_id IS NULL OR z.zone_id IS NULL OR z.session_id <> o.session_id
   OR (i.requested_seat_id IS NOT NULL AND (s.seat_id IS NULL OR s.zone_id <> i.zone_id));
SELECT 'TICKET_ITEM_SEAT' AS violation, CAST(t.ticket_id AS CHAR) AS resource_id
FROM ticket t LEFT JOIN ticket_order_item i ON i.order_item_id = t.order_item_id
LEFT JOIN session_seat s ON s.seat_id = t.seat_id
WHERE i.order_item_id IS NULL OR s.seat_id IS NULL OR i.zone_id <> s.zone_id;
SELECT 'COUNTER_USER_SESSION' AS violation, CONCAT(p.user_id, ':', p.session_id) AS resource_id
FROM user_session_purchase p LEFT JOIN app_user u ON u.id = p.user_id
LEFT JOIN activity_session s ON s.activity_session_id = p.session_id WHERE u.id IS NULL OR s.activity_session_id IS NULL;
SELECT 'WHITELIST_SCOPE' AS violation, CAST(c.config_id AS CHAR) AS resource_id
FROM whitelist_config c LEFT JOIN activity a ON c.scope_type = 'ACTIVITY' AND a.activity_id = c.scope_id
LEFT JOIN activity_session s ON c.scope_type = 'SESSION' AND s.activity_session_id = c.scope_id
WHERE (c.scope_type = 'ACTIVITY' AND a.activity_id IS NULL) OR (c.scope_type = 'SESSION' AND s.activity_session_id IS NULL);
SELECT 'WHITELIST_MEMBER' AS violation, CAST(m.member_id AS CHAR) AS resource_id
FROM whitelist_member m LEFT JOIN whitelist_config c ON c.config_id = m.config_id
LEFT JOIN app_user u ON u.id = m.user_id WHERE c.config_id IS NULL OR u.id IS NULL;
SELECT 'RESERVATION_USER_SESSION' AS violation, CAST(r.reservation_id AS CHAR) AS resource_id
FROM free_reservation r LEFT JOIN app_user u ON u.id = r.user_id
LEFT JOIN activity_session s ON s.activity_session_id = r.session_id WHERE u.id IS NULL OR s.activity_session_id IS NULL;
SELECT 'QUOTA_SESSION' AS violation, CAST(q.session_id AS CHAR) AS resource_id
FROM free_session_quota q LEFT JOIN activity_session s ON s.activity_session_id = q.session_id WHERE s.activity_session_id IS NULL;
SELECT 'REFUND_ORDER_BUYER' AS violation, CAST(r.refund_id AS CHAR) AS resource_id
FROM ticket_refund_request r LEFT JOIN ticket_order o ON o.order_id = r.order_id
LEFT JOIN app_user u ON u.id = r.buyer_id WHERE o.order_id IS NULL OR u.id IS NULL OR r.buyer_id <> o.buyer_id;
SELECT 'REFUND_ITEM_REQUEST_TICKET' AS violation, CAST(i.refund_item_id AS CHAR) AS resource_id
FROM ticket_refund_item i LEFT JOIN ticket_refund_request r ON r.refund_id = i.refund_id
LEFT JOIN ticket t ON t.ticket_id = i.ticket_id WHERE r.refund_id IS NULL OR t.ticket_id IS NULL;
SELECT 'GIFT_OPERATION_SCOPE' AS violation, CAST(o.operation_id AS CHAR) AS resource_id
FROM gift_inventory_operation o LEFT JOIN app_user u ON u.id = o.publisher_id
LEFT JOIN activity_session s ON s.activity_session_id = o.session_id LEFT JOIN session_zone z ON z.zone_id = o.zone_id
WHERE u.id IS NULL OR s.activity_session_id IS NULL OR z.zone_id IS NULL OR z.session_id <> o.session_id OR z.zone_type <> 'GIFT';
SELECT 'GIFT_ITEM_OPERATION_SEAT' AS violation, CAST(i.operation_item_id AS CHAR) AS resource_id
FROM gift_inventory_operation_item i LEFT JOIN gift_inventory_operation o ON o.operation_id = i.operation_id
LEFT JOIN session_seat s ON s.seat_id = i.seat_id WHERE o.operation_id IS NULL OR s.seat_id IS NULL OR o.zone_id <> s.zone_id;
