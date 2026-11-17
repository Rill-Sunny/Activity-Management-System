SELECT 'PRODUCT_ORDER_OWNER' AS violation, CAST(o.order_id AS CHAR) AS resource_id
FROM product_order o LEFT JOIN app_user b ON b.id=o.buyer_id LEFT JOIN app_user p ON p.id=o.publisher_id
WHERE b.id IS NULL OR p.id IS NULL;
SELECT 'PRODUCT_ITEM_SCOPE' AS violation, CAST(i.order_item_id AS CHAR) AS resource_id
FROM product_order_item i LEFT JOIN product_order o ON o.order_id=i.order_id
LEFT JOIN product p ON p.product_id=i.product_id LEFT JOIN sku s ON s.sku_id=i.sku_id
WHERE o.order_id IS NULL OR p.product_id IS NULL OR s.sku_id IS NULL
   OR s.product_id<>i.product_id OR p.publisher_id<>o.publisher_id;
SELECT 'PRODUCT_PURCHASE_SCOPE' AS violation, CONCAT(c.user_id,':',c.product_id) AS resource_id
FROM user_product_purchase c LEFT JOIN app_user u ON u.id=c.user_id LEFT JOIN product p ON p.product_id=c.product_id
WHERE u.id IS NULL OR p.product_id IS NULL;
SELECT 'PRODUCT_AFTER_SALE_ORDER' AS violation, CAST(r.after_sale_request_id AS CHAR) AS resource_id
FROM product_after_sale_request r LEFT JOIN product_order o ON o.order_id=r.order_id
WHERE o.order_id IS NULL OR r.buyer_id<>o.buyer_id;
SELECT 'PRODUCT_AFTER_SALE_ITEM' AS violation, CAST(a.after_sale_item_id AS CHAR) AS resource_id
FROM product_after_sale_item a LEFT JOIN product_after_sale_request r ON r.after_sale_request_id=a.after_sale_request_id
LEFT JOIN product_order_item i ON i.order_item_id=a.order_item_id
WHERE r.after_sale_request_id IS NULL OR i.order_item_id IS NULL OR i.order_id<>r.order_id;
SELECT 'QUALIFICATION_TASK_SCOPE' AS violation, CAST(t.task_id AS CHAR) AS resource_id
FROM ticket_qualification_after_sale_task t LEFT JOIN product_order_item i ON i.order_item_id=t.product_order_item_id
LEFT JOIN outbox_event e ON e.event_id=t.source_event_id
WHERE i.order_item_id IS NULL OR e.event_id IS NULL OR e.event_type<>'TICKET_QUALIFICATION_CHANGED';
