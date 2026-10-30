-- Application acceptance only. Resource restoration belongs to the later processing transaction.
-- All references are logical, consistent with the existing schema.
CREATE TABLE ticket_refund_request (
    refund_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    buyer_id BIGINT NOT NULL,
    order_id BIGINT NOT NULL,
    request_id VARCHAR(64) COLLATE utf8mb4_0900_bin NOT NULL,
    reason VARCHAR(500) NOT NULL,
    requested_at DATETIME(3) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    policy_version VARCHAR(32) NOT NULL DEFAULT 'ORDINARY_V1',
    open_time_snapshot DATETIME(3) NOT NULL,
    start_time_snapshot DATETIME(3) NOT NULL,
    CONSTRAINT chk_refund_request_ids CHECK (buyer_id > 0 AND order_id > 0),
    CONSTRAINT chk_refund_request_text CHECK (CHAR_LENGTH(TRIM(request_id)) > 0 AND CHAR_LENGTH(TRIM(reason)) > 0),
    CONSTRAINT chk_refund_request_status CHECK (status IN ('PENDING', 'PROCESSING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT chk_refund_request_time CHECK (requested_at < start_time_snapshot),
    CONSTRAINT uq_refund_request UNIQUE (buyer_id, request_id),
    INDEX idx_refund_request_order (order_id, refund_id),
    INDEX idx_refund_request_processing (status, refund_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ticket_refund_item (
    refund_item_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    refund_id BIGINT NOT NULL,
    ticket_id BIGINT NOT NULL,
    original_price DECIMAL(10,2) NOT NULL,
    fee_rate DECIMAL(5,4) NOT NULL,
    refund_amount DECIMAL(10,2) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    failure_reason VARCHAR(500) NULL,
    claimed_ticket_id BIGINT GENERATED ALWAYS AS (
        CASE WHEN status IN ('PENDING', 'PROCESSING', 'SUCCEEDED') THEN ticket_id ELSE NULL END
    ) VIRTUAL,
    CONSTRAINT chk_refund_item_ids CHECK (refund_id > 0 AND ticket_id > 0),
    CONSTRAINT chk_refund_item_price CHECK (original_price > 0),
    CONSTRAINT chk_refund_item_rate CHECK (fee_rate >= 0 AND fee_rate <= 1),
    CONSTRAINT chk_refund_item_amount CHECK (
        refund_amount >= 0 AND refund_amount <= original_price
        AND refund_amount = ROUND(original_price * (1 - fee_rate), 2)
    ),
    CONSTRAINT chk_refund_item_status CHECK (status IN ('PENDING', 'PROCESSING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT chk_refund_item_failure CHECK (
        (status = 'FAILED' AND failure_reason IS NOT NULL AND CHAR_LENGTH(TRIM(failure_reason)) > 0)
        OR (status <> 'FAILED' AND failure_reason IS NULL)
    ),
    CONSTRAINT uq_refund_item_ticket UNIQUE (refund_id, ticket_id),
    CONSTRAINT uq_refund_ticket_claim UNIQUE (claimed_ticket_id),
    INDEX idx_refund_item_ticket_history (ticket_id, refund_item_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
