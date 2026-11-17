ALTER TABLE outbox_event
    DROP CHECK chk_outbox_event_type,
    ADD CONSTRAINT chk_outbox_event_type CHECK (
        event_type IN ('ORDER_EXPIRED', 'PAYMENT_SUCCEEDED', 'REFUND_SUCCEEDED', 'TICKET_QUALIFICATION_CHANGED')
    );

CREATE TABLE ticket_qualification_after_sale_task (
    task_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    source_event_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
    product_order_item_id BIGINT NOT NULL,
    qualification_scope VARCHAR(16) COLLATE utf8mb4_0900_bin NOT NULL,
    status VARCHAR(20) COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'PENDING',
    attempt_count INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(3) NULL DEFAULT CURRENT_TIMESTAMP(3),
    failure_reason VARCHAR(1000) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT chk_ticket_qualification_task_item CHECK (product_order_item_id > 0),
    CONSTRAINT chk_ticket_qualification_task_scope CHECK (qualification_scope IN ('ACTIVITY', 'SESSION')),
    CONSTRAINT chk_ticket_qualification_task_status CHECK (
        status IN ('PENDING', 'PROCESSING', 'WAITING_RETURN', 'SKIPPED', 'SUCCEEDED', 'FAILED')
    ),
    CONSTRAINT chk_ticket_qualification_task_attempts CHECK (attempt_count BETWEEN 0 AND 5),
    CONSTRAINT chk_ticket_qualification_task_failure CHECK (
        (status = 'FAILED' AND failure_reason IS NOT NULL AND CHAR_LENGTH(TRIM(failure_reason)) > 0)
        OR (status <> 'FAILED' AND failure_reason IS NULL)
    ),
    CONSTRAINT chk_ticket_qualification_task_schedule CHECK (
        (status = 'WAITING_RETURN' AND next_attempt_at IS NULL)
        OR (status = 'SKIPPED' AND next_attempt_at IS NULL)
        OR (status = 'SUCCEEDED' AND next_attempt_at IS NULL)
        OR (status = 'FAILED' AND attempt_count >= 5 AND next_attempt_at IS NULL)
        OR (status IN ('PENDING', 'PROCESSING') AND next_attempt_at IS NOT NULL)
        OR (status = 'FAILED' AND attempt_count < 5 AND next_attempt_at IS NOT NULL)
    ),
    CONSTRAINT uq_ticket_qualification_task_order_item UNIQUE (product_order_item_id),
    INDEX idx_ticket_qualification_task_source (source_event_id, task_id),
    INDEX idx_ticket_qualification_task_due (status, next_attempt_at, task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
