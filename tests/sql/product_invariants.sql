-- Catalog reconciliation: remaining stock changes after payment or stock adjustments.
SELECT 'PRODUCT_ACTIVITY_OWNER' AS violation, p.product_id AS resource_id
FROM product p LEFT JOIN activity a ON a.activity_id=p.activity_id LEFT JOIN app_user u ON u.id=p.publisher_id
WHERE a.activity_id IS NULL OR u.id IS NULL OR p.publisher_id<>a.publisher_id;
SELECT 'PRODUCT_QUALIFICATION_SCOPE' AS violation,p.product_id AS resource_id
FROM product p LEFT JOIN activity a ON a.activity_id=p.activity_id
LEFT JOIN activity_session s ON s.activity_session_id=p.qualification_session_id
WHERE p.qualification_type='TICKET_REQUIRED' AND
  (a.type<>'PAID' OR (p.qualification_scope='SESSION' AND (s.activity_session_id IS NULL OR s.activity_id<>p.activity_id)));
SELECT 'SKU_PRODUCT_OR_STOCK' AS violation,s.sku_id AS resource_id
FROM sku s LEFT JOIN product p ON p.product_id=s.product_id
WHERE p.product_id IS NULL OR s.total_stock<0 OR s.remains_stock<0 OR s.remains_stock>s.total_stock;
SELECT 'PRODUCT_MEMBER_SCOPE' AS violation,m.member_id AS resource_id
FROM product_whitelist_member m LEFT JOIN product p ON p.product_id=m.product_id LEFT JOIN app_user u ON u.id=m.user_id
WHERE p.product_id IS NULL OR u.id IS NULL OR p.qualification_type<>'PRODUCT_WHITELIST';
SELECT 'PRODUCT_WITHOUT_ACTIVE_SKU' AS violation,p.product_id AS resource_id
FROM product p LEFT JOIN sku s ON s.product_id=p.product_id AND s.status='ACTIVE'
WHERE p.status='ON_SALE' GROUP BY p.product_id HAVING COUNT(s.sku_id)=0;
