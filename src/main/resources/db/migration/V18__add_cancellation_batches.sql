CREATE TABLE cancellation_event (
    event_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    cancel_type VARCHAR(16) COLLATE utf8mb4_0900_bin NOT NULL,
    target_id BIGINT NOT NULL,
    activity_id BIGINT NOT NULL,
    publisher_id BIGINT NOT NULL,
    operator_id BIGINT NOT NULL,
    reason VARCHAR(500) NOT NULL,
    cancelled_at DATETIME(3) NOT NULL,
    request_id VARCHAR(64) COLLATE utf8mb4_0900_bin NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uq_cancellation_target (cancel_type,target_id),
    UNIQUE KEY uq_cancellation_request (publisher_id,request_id),
    CONSTRAINT chk_cancellation_event CHECK (cancel_type IN ('SESSION','ACTIVITY') AND target_id>0 AND activity_id>0
        AND publisher_id>0 AND operator_id=publisher_id AND CHAR_LENGTH(TRIM(reason))>0 AND CHAR_LENGTH(TRIM(request_id))>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE cancellation_scope (
    event_id BIGINT NOT NULL,
    session_id BIGINT NOT NULL,
    PRIMARY KEY(event_id,session_id),
    UNIQUE KEY uq_cancellation_session (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE cancellation_batch (
    batch_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    event_id BIGINT NOT NULL,
    status VARCHAR(20) COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'PENDING',
    scan_phase INT NOT NULL DEFAULT 0,
    next_cursor BIGINT NOT NULL DEFAULT 0,
    total_items INT NOT NULL DEFAULT 0,
    succeeded_items INT NOT NULL DEFAULT 0,
    failed_items INT NOT NULL DEFAULT 0,
    started_at DATETIME(3) NULL,
    completed_at DATETIME(3) NULL,
    UNIQUE KEY uq_cancellation_batch_event(event_id),
    CONSTRAINT chk_cancellation_batch CHECK (status IN ('PENDING','PROCESSING','COMPLETED','PARTIAL_FAILED')
        AND scan_phase BETWEEN 0 AND 4 AND next_cursor>=0 AND total_items>=0 AND succeeded_items>=0
        AND failed_items>=0 AND succeeded_items+failed_items<=total_items
        AND (status<>'COMPLETED' OR (scan_phase=4 AND succeeded_items=total_items AND completed_at IS NOT NULL)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE cancellation_batch_item (
    item_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    batch_id BIGINT NOT NULL,
    target_type VARCHAR(16) COLLATE utf8mb4_0900_bin NOT NULL,
    target_id BIGINT NOT NULL,
    session_id BIGINT NOT NULL,
    action VARCHAR(24) COLLATE utf8mb4_0900_bin NOT NULL,
    status VARCHAR(20) COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'PENDING',
    attempt_count INT NOT NULL DEFAULT 0,
    retry_count INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    lease_until DATETIME(3) NULL,
    claim_token VARCHAR(36) COLLATE utf8mb4_0900_bin NULL,
    failure_reason VARCHAR(1000) NULL,
    processed_at DATETIME(3) NULL,
    refund_id BIGINT NULL,
    UNIQUE KEY uq_cancellation_item(batch_id,target_type,target_id,action),
    INDEX idx_cancellation_work(status,next_attempt_at,item_id),
    INDEX idx_cancellation_lease(status,lease_until,item_id),
    CONSTRAINT chk_cancellation_item CHECK (batch_id>0 AND target_id>0 AND session_id>0 AND attempt_count>=0 AND retry_count>=0
        AND ((target_type='ORDER' AND action='CLOSE') OR (target_type='TICKET' AND action='REFUND')
          OR (target_type IN ('RESERVATION','GIFT') AND action='INVALIDATE'))
        AND status IN ('PENDING','PROCESSING','RETRY_PENDING','FAILED','SUCCEEDED')
        AND ((status='PROCESSING' AND lease_until IS NOT NULL AND claim_token IS NOT NULL)
          OR (status<>'PROCESSING' AND lease_until IS NULL AND claim_token IS NULL))
        AND (status<>'SUCCEEDED' OR processed_at IS NOT NULL)
        AND (status NOT IN ('FAILED','RETRY_PENDING') OR failure_reason IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE ticket_refund_request DROP CHECK chk_refund_policy_source, DROP CHECK chk_refund_request_time,
    ADD CONSTRAINT chk_refund_policy_source CHECK (
        (refund_policy='NORMAL_REFUND' AND source_event_id IS NULL)
        OR (refund_policy IN ('SESSION_RESCHEDULE_REFUND','CANCELLATION_REFUND') AND source_event_id IS NOT NULL
            AND source_event_id>0 AND accepted_at IS NOT NULL AND accepted_at=requested_at)),
    ADD CONSTRAINT chk_refund_request_time CHECK (refund_policy='CANCELLATION_REFUND' OR requested_at<start_time_snapshot);

ALTER TABLE session_seat DROP CHECK chk_seat_allocation_status,
    ADD CONSTRAINT chk_seat_allocation_status CHECK (allocation_status IN ('AVAILABLE','SOLD','GIFT_ALLOCATED','GIFT_CANCELLED'));
ALTER TABLE gift_inventory_operation_item
    ADD COLUMN cancelled_at DATETIME(3) NULL,
    ADD COLUMN cancellation_event_id BIGINT NULL,
    ADD CONSTRAINT chk_gift_cancellation CHECK ((cancelled_at IS NULL AND cancellation_event_id IS NULL)
        OR (cancelled_at IS NOT NULL AND cancellation_event_id IS NOT NULL AND cancellation_event_id>0));
ALTER TABLE outbox_event DROP CHECK chk_outbox_event_type,
    ADD CONSTRAINT chk_outbox_event_type CHECK (event_type IN ('ORDER_EXPIRED','PAYMENT_SUCCEEDED','REFUND_SUCCEEDED',
        'TICKET_QUALIFICATION_CHANGED','SESSION_RESCHEDULED','RESCHEDULE_WINDOW_CLOSED','SESSION_CANCELLED','ACTIVITY_CANCELLED'));
