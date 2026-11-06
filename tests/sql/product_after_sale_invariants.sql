-- Every SELECT returns violating rows; a valid after-sale reconciliation returns no rows.
SELECT 'ORDER_ITEM_REFUND_LIMIT' AS violation, i.order_item_id AS resource_id
FROM product_order_item i
WHERE i.refund_pending_quantity < 0 OR i.refunded_quantity < 0
   OR i.refunded_quantity + i.refund_pending_quantity > i.quantity
   OR i.returned_quantity > i.shipped_quantity
   OR i.returned_quantity > i.refunded_quantity
   OR i.restocked_quantity > i.refunded_quantity;

SELECT 'PENDING_RESERVATION_MISMATCH' AS violation, i.order_item_id AS resource_id
FROM product_order_item i
LEFT JOIN (
    SELECT a.order_item_id, SUM(a.requested_quantity-a.refunded_quantity) AS reserved_quantity
    FROM product_after_sale_item a
    JOIN product_after_sale_request r ON r.after_sale_request_id=a.after_sale_request_id
    WHERE r.status IN ('PENDING','PROCESSING') AND a.status IN ('PENDING','PROCESSING')
    GROUP BY a.order_item_id
) p ON p.order_item_id=i.order_item_id
WHERE i.refund_pending_quantity <> COALESCE(p.reserved_quantity,0);

SELECT 'REFUNDED_COUNTER_MISMATCH' AS violation, i.order_item_id AS resource_id
FROM product_order_item i
LEFT JOIN (
    SELECT a.order_item_id, SUM(a.refunded_quantity) AS refunded_quantity
    FROM product_after_sale_item a
    JOIN product_after_sale_request r ON r.after_sale_request_id=a.after_sale_request_id
    WHERE r.status='SUCCEEDED' AND a.status='SUCCEEDED'
    GROUP BY a.order_item_id
) a ON a.order_item_id=i.order_item_id
WHERE i.refunded_quantity <> COALESCE(a.refunded_quantity,0);

SELECT 'RETURNED_COUNTER_MISMATCH' AS violation, i.order_item_id AS resource_id
FROM product_order_item i
LEFT JOIN (
    SELECT a.order_item_id, SUM(a.returned_quantity) AS returned_quantity
    FROM product_after_sale_item a
    JOIN product_after_sale_request r ON r.after_sale_request_id=a.after_sale_request_id
    WHERE r.status='SUCCEEDED' AND a.status='SUCCEEDED'
    GROUP BY a.order_item_id
) a ON a.order_item_id=i.order_item_id
WHERE i.returned_quantity <> COALESCE(a.returned_quantity,0);

SELECT 'RESTOCKED_COUNTER_MISMATCH' AS violation, i.order_item_id AS resource_id
FROM product_order_item i
LEFT JOIN (
    SELECT a.order_item_id,
           SUM(CASE WHEN a.inspection_result='NOT_REQUIRED' OR a.is_resalable=TRUE
                    THEN a.refunded_quantity ELSE 0 END) AS restocked_quantity
    FROM product_after_sale_item a
    JOIN product_after_sale_request r ON r.after_sale_request_id=a.after_sale_request_id
    WHERE r.status='SUCCEEDED' AND a.status='SUCCEEDED'
    GROUP BY a.order_item_id
) a ON a.order_item_id=i.order_item_id
WHERE i.restocked_quantity <> COALESCE(a.restocked_quantity,0);

SELECT 'PRODUCT_VALID_COUNT_MISMATCH' AS violation, k.product_id AS resource_id
FROM (
    SELECT user_id, product_id FROM user_product_purchase
    UNION
    SELECT o.buyer_id AS user_id, i.product_id
    FROM product_order o JOIN product_order_item i ON i.order_id=o.order_id
    WHERE o.status IN ('PAID','PARTIALLY_REFUNDED','REFUNDED')
) k
LEFT JOIN user_product_purchase p ON p.user_id=k.user_id AND p.product_id=k.product_id
LEFT JOIN (
    SELECT o.buyer_id, i.product_id, SUM(i.quantity-i.refunded_quantity) AS valid_quantity
    FROM product_order o JOIN product_order_item i ON i.order_id=o.order_id
    WHERE o.status IN ('PAID','PARTIALLY_REFUNDED','REFUNDED')
    GROUP BY o.buyer_id,i.product_id
) q ON q.buyer_id=k.user_id AND q.product_id=k.product_id
WHERE COALESCE(p.valid_count,0) <> COALESCE(q.valid_quantity,0);

SELECT 'SKU_AVAILABLE_STOCK_MISMATCH' AS violation, k.sku_id AS resource_id
FROM (
    SELECT sku_id FROM sku
    UNION
    SELECT i.sku_id
    FROM product_order o JOIN product_order_item i ON i.order_id=o.order_id
    WHERE o.status IN ('PAID','PARTIALLY_REFUNDED','REFUNDED')
) k
LEFT JOIN sku s ON s.sku_id=k.sku_id
LEFT JOIN (
    SELECT i.sku_id, SUM(i.quantity-i.restocked_quantity) AS net_sold
    FROM product_order o JOIN product_order_item i ON i.order_id=o.order_id
    WHERE o.status IN ('PAID','PARTIALLY_REFUNDED','REFUNDED')
    GROUP BY i.sku_id
) q ON q.sku_id=k.sku_id
WHERE s.sku_id IS NULL OR s.remains_stock <> s.total_stock-COALESCE(q.net_sold,0);

SELECT 'ORDER_REFUND_SUMMARY_MISMATCH' AS violation, o.order_id AS resource_id
FROM product_order o
JOIN (
    SELECT order_id, SUM(quantity) AS purchased_quantity, SUM(refunded_quantity) AS refunded_quantity
    FROM product_order_item GROUP BY order_id
) i ON i.order_id=o.order_id
WHERE o.status IN ('PAID','PARTIALLY_REFUNDED','REFUNDED')
  AND ((i.refunded_quantity=0 AND o.status<>'PAID')
    OR (i.refunded_quantity>0 AND i.refunded_quantity<i.purchased_quantity AND o.status<>'PARTIALLY_REFUNDED')
    OR (i.refunded_quantity=i.purchased_quantity AND o.status<>'REFUNDED'));

SELECT 'FAILED_REQUEST_NOT_RETRYABLE' AS violation, r.after_sale_request_id AS resource_id
FROM product_after_sale_request r
LEFT JOIN product_after_sale_item i ON i.after_sale_request_id=r.after_sale_request_id
WHERE r.status='FAILED'
GROUP BY r.after_sale_request_id,r.failure_reason
HAVING r.failure_reason IS NULL OR TRIM(r.failure_reason)=''
    OR COUNT(i.after_sale_item_id)=0
    OR SUM(i.status<>'FAILED')>0
    OR SUM(i.refunded_quantity)<>0
    OR SUM(i.returned_quantity)<>0;
