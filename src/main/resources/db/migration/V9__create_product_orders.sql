CREATE TABLE product_order (
    order_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    buyer_id BIGINT NOT NULL,
    publisher_id BIGINT NOT NULL,
    request_id VARCHAR(64) COLLATE utf8mb4_0900_bin NOT NULL,
    total_amount DECIMAL(12,2) NOT NULL,
    status VARCHAR(30) COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'PENDING_PAYMENT',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    expires_at DATETIME(3) GENERATED ALWAYS AS (DATE_ADD(created_at, INTERVAL 15 MINUTE)) STORED,
    paid_at DATETIME(3) NULL,
    closed_reason VARCHAR(100) NULL,
    CONSTRAINT chk_product_order_ids CHECK (buyer_id > 0 AND publisher_id > 0),
    CONSTRAINT chk_product_order_amount CHECK (total_amount > 0),
    CONSTRAINT chk_product_order_request CHECK (CHAR_LENGTH(TRIM(request_id)) > 0),
    CONSTRAINT chk_product_order_status CHECK (status IN ('PENDING_PAYMENT', 'PAID', 'PARTIALLY_REFUNDED', 'REFUNDED', 'CLOSED')),
    CONSTRAINT chk_product_order_paid_at CHECK (
        (status IN ('PENDING_PAYMENT', 'CLOSED') AND paid_at IS NULL)
        OR (status IN ('PAID', 'PARTIALLY_REFUNDED', 'REFUNDED') AND paid_at IS NOT NULL)
    ),
    CONSTRAINT chk_product_order_closed_reason CHECK (
        (status = 'CLOSED' AND closed_reason IS NOT NULL AND CHAR_LENGTH(TRIM(closed_reason)) > 0)
        OR (status <> 'CLOSED' AND closed_reason IS NULL)
    ),
    CONSTRAINT uq_product_order_request UNIQUE (buyer_id, request_id),
    INDEX idx_product_order_buyer_created (buyer_id, created_at, order_id),
    INDEX idx_product_order_publisher_status (publisher_id, status, order_id),
    INDEX idx_product_order_expiry (status, expires_at, order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE product_order_item (
    order_item_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    sku_id BIGINT NOT NULL,
    quantity INT NOT NULL,
    unit_price DECIMAL(10,2) NOT NULL,
    product_name_snapshot VARCHAR(100) NOT NULL,
    sku_name_snapshot VARCHAR(100) NOT NULL,
    activity_id_snapshot BIGINT NOT NULL,
    qualification_type_snapshot VARCHAR(24) COLLATE utf8mb4_0900_bin NOT NULL,
    qualification_scope_snapshot VARCHAR(16) COLLATE utf8mb4_0900_bin NULL,
    qualification_session_id_snapshot BIGINT NULL,
    refund_policy_template_snapshot VARCHAR(32) COLLATE utf8mb4_0900_bin NOT NULL,
    refund_policy_version_snapshot SMALLINT NOT NULL,
    special_policy_acknowledged BOOLEAN NOT NULL DEFAULT FALSE,
    policy_acknowledged_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT chk_product_order_item_ids CHECK (order_id > 0 AND product_id > 0 AND sku_id > 0 AND activity_id_snapshot > 0),
    CONSTRAINT chk_product_order_item_quantity CHECK (quantity > 0),
    CONSTRAINT chk_product_order_item_price CHECK (unit_price > 0),
    CONSTRAINT chk_product_order_item_names CHECK (
        CHAR_LENGTH(TRIM(product_name_snapshot)) > 0 AND CHAR_LENGTH(TRIM(sku_name_snapshot)) > 0
    ),
    CONSTRAINT chk_product_order_item_qualification CHECK (
        (qualification_type_snapshot IN ('PUBLIC', 'PRODUCT_WHITELIST')
            AND qualification_scope_snapshot IS NULL AND qualification_session_id_snapshot IS NULL)
        OR (qualification_type_snapshot = 'TICKET_REQUIRED' AND (
            (qualification_scope_snapshot = 'ACTIVITY' AND qualification_session_id_snapshot IS NULL)
            OR (qualification_scope_snapshot = 'SESSION' AND qualification_session_id_snapshot IS NOT NULL AND qualification_session_id_snapshot > 0)
        ))
    ),
    CONSTRAINT chk_product_order_item_refund_policy CHECK (
        refund_policy_template_snapshot IN ('STANDARD_7D', 'SPECIAL_NO_REASON_RETURN') AND refund_policy_version_snapshot > 0
    ),
    CONSTRAINT chk_product_order_item_policy_ack CHECK (
        (refund_policy_template_snapshot = 'SPECIAL_NO_REASON_RETURN'
            AND special_policy_acknowledged = TRUE AND policy_acknowledged_at IS NOT NULL)
        OR (refund_policy_template_snapshot = 'STANDARD_7D'
            AND special_policy_acknowledged = FALSE AND policy_acknowledged_at IS NULL)
    ),
    CONSTRAINT uq_product_order_item_sku UNIQUE (order_id, sku_id),
    INDEX idx_product_order_item_product (product_id, order_id),
    INDEX idx_product_order_item_sku (sku_id, order_id),
    INDEX idx_product_order_item_qualification (
        activity_id_snapshot, qualification_type_snapshot, qualification_session_id_snapshot, order_id
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE user_product_purchase (
    user_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    valid_count INT NOT NULL DEFAULT 0,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT pk_user_product_purchase PRIMARY KEY (user_id, product_id),
    CONSTRAINT chk_user_product_purchase_ids CHECK (user_id > 0 AND product_id > 0),
    CONSTRAINT chk_user_product_purchase_count CHECK (valid_count >= 0),
    INDEX idx_user_product_purchase_product (product_id, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
