-- Freeze product fulfillment and after-sales quantity facts.
-- References remain logical; this migration adds no physical foreign keys.
ALTER TABLE product_order_item
    ADD COLUMN fulfillment_status VARCHAR(24) COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'UNSHIPPED',
    ADD COLUMN shipped_quantity INT NOT NULL DEFAULT 0,
    ADD COLUMN received_quantity INT NOT NULL DEFAULT 0,
    ADD COLUMN shipped_at DATETIME(3) NULL,
    ADD COLUMN received_at DATETIME(3) NULL,
    ADD COLUMN refund_pending_quantity INT NOT NULL DEFAULT 0,
    ADD COLUMN refunded_quantity INT NOT NULL DEFAULT 0,
    ADD COLUMN returned_quantity INT NOT NULL DEFAULT 0,
    ADD COLUMN restocked_quantity INT NOT NULL DEFAULT 0,
    ADD INDEX idx_product_order_item_order_item (order_id, order_item_id),
    ADD CONSTRAINT chk_product_order_item_fulfillment_quantity CHECK (
        shipped_quantity >= 0
        AND shipped_quantity <= quantity
        AND received_quantity >= 0
        AND received_quantity <= shipped_quantity
    ),
    ADD CONSTRAINT chk_product_order_item_fulfillment_state CHECK (
        (fulfillment_status = 'UNSHIPPED'
            AND shipped_quantity = 0 AND received_quantity = 0
            AND shipped_at IS NULL AND received_at IS NULL)
        OR (fulfillment_status = 'SHIPPED'
            AND shipped_quantity > 0 AND received_quantity = 0
            AND shipped_at IS NOT NULL AND received_at IS NULL)
        OR (fulfillment_status = 'RECEIVED'
            AND shipped_quantity > 0 AND received_quantity = shipped_quantity
            AND shipped_at IS NOT NULL AND received_at IS NOT NULL)
    ),
    ADD CONSTRAINT chk_product_order_item_fulfillment_time CHECK (
        received_at IS NULL OR (shipped_at IS NOT NULL AND received_at >= shipped_at)
    ),
    ADD CONSTRAINT chk_product_order_item_after_sale_quantities CHECK (
        refund_pending_quantity >= 0
        AND refunded_quantity >= 0
        AND refund_pending_quantity + refunded_quantity <= quantity
        AND returned_quantity >= 0
        AND returned_quantity <= shipped_quantity
        AND returned_quantity <= refunded_quantity
        AND restocked_quantity >= 0
        AND restocked_quantity <= refunded_quantity
    );

CREATE TABLE product_after_sale_request (
    after_sale_request_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    buyer_id BIGINT NOT NULL,
    request_id VARCHAR(64) COLLATE utf8mb4_0900_bin NOT NULL,
    reason VARCHAR(500) NOT NULL,
    requested_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    status VARCHAR(20) COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'PENDING',
    failure_reason VARCHAR(500) NULL,
    CONSTRAINT chk_product_after_sale_request_ids CHECK (
        order_id > 0 AND buyer_id > 0
    ),
    CONSTRAINT chk_product_after_sale_request_text CHECK (
        CHAR_LENGTH(TRIM(request_id)) > 0 AND CHAR_LENGTH(TRIM(reason)) > 0
    ),
    CONSTRAINT chk_product_after_sale_request_status CHECK (
        status IN ('PENDING', 'PROCESSING', 'SUCCEEDED', 'FAILED')
    ),
    CONSTRAINT chk_product_after_sale_request_failure CHECK (
        (status = 'FAILED' AND failure_reason IS NOT NULL AND CHAR_LENGTH(TRIM(failure_reason)) > 0)
        OR (status <> 'FAILED' AND failure_reason IS NULL)
    ),
    CONSTRAINT uq_product_after_sale_request UNIQUE (buyer_id, request_id),
    INDEX idx_product_after_sale_request_order (order_id, requested_at, after_sale_request_id),
    INDEX idx_product_after_sale_request_processing (status, requested_at, after_sale_request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE product_after_sale_item (
    after_sale_item_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    after_sale_request_id BIGINT NOT NULL,
    order_item_id BIGINT NOT NULL,
    sku_id BIGINT NOT NULL,
    reason_code VARCHAR(32) COLLATE utf8mb4_0900_bin NOT NULL,
    requested_quantity INT NOT NULL,
    simulate_resalable BOOLEAN NOT NULL DEFAULT TRUE,
    status VARCHAR(20) COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'PENDING',
    returned_quantity INT NOT NULL DEFAULT 0,
    refunded_quantity INT NOT NULL DEFAULT 0,
    returned_at DATETIME(3) NULL,
    inspection_result VARCHAR(24) COLLATE utf8mb4_0900_bin NOT NULL,
    is_resalable BOOLEAN NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT chk_product_after_sale_item_ids CHECK (
        after_sale_request_id > 0 AND order_item_id > 0 AND sku_id > 0
    ),
    CONSTRAINT chk_product_after_sale_item_quantities CHECK (
        requested_quantity > 0
        AND returned_quantity >= 0 AND returned_quantity <= requested_quantity
        AND refunded_quantity >= 0 AND refunded_quantity <= requested_quantity
    ),
    CONSTRAINT chk_product_after_sale_item_reason CHECK (
        reason_code IN ('NO_REASON', 'QUALITY_PROBLEM', 'TICKET_QUALIFICATION_LOST', 'ACTIVITY_CANCELLED')
    ),
    CONSTRAINT chk_product_after_sale_item_status CHECK (
        status IN ('PENDING', 'PROCESSING', 'SUCCEEDED', 'FAILED')
    ),
    CONSTRAINT chk_product_after_sale_item_return_time CHECK (
        (returned_quantity = 0 AND returned_at IS NULL)
        OR (returned_quantity > 0 AND returned_at IS NOT NULL)
    ),
    CONSTRAINT chk_product_after_sale_item_inspection CHECK (
        (inspection_result = 'NOT_REQUIRED' AND returned_quantity = 0 AND is_resalable IS NULL)
        OR (inspection_result = 'PENDING' AND is_resalable IS NULL)
        OR (inspection_result = 'PASSED' AND returned_quantity > 0 AND is_resalable IS NOT NULL)
        OR (inspection_result = 'FAILED' AND returned_quantity > 0 AND is_resalable = FALSE)
    ),
    CONSTRAINT uq_product_after_sale_item_order_item UNIQUE (after_sale_request_id, order_item_id),
    INDEX idx_product_after_sale_item_order_item (order_item_id, after_sale_item_id),
    INDEX idx_product_after_sale_item_sku (sku_id, after_sale_item_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
