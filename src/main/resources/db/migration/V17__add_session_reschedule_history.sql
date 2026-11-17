-- F11: immutable events and affected tickets; all references remain logical.
CREATE TABLE session_reschedule_event (
    event_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    session_id BIGINT NOT NULL,
    request_id VARCHAR(64) COLLATE utf8mb4_0900_bin NOT NULL,
    old_start_time DATETIME(3) NOT NULL,
    old_end_time DATETIME(3) NOT NULL,
    new_start_time DATETIME(3) NOT NULL,
    new_end_time DATETIME(3) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    operator_id BIGINT NOT NULL,
    announcement_time DATETIME(3) NOT NULL,
    special_refund_deadline DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT uq_reschedule_request UNIQUE (session_id, request_id),
    CONSTRAINT chk_reschedule_ids CHECK (session_id > 0 AND operator_id > 0),
    CONSTRAINT chk_reschedule_text CHECK (CHAR_LENGTH(TRIM(request_id)) > 0 AND CHAR_LENGTH(TRIM(reason)) > 0),
    CONSTRAINT chk_reschedule_times CHECK (old_start_time < old_end_time AND new_start_time < new_end_time
        AND announcement_time < new_start_time AND announcement_time < special_refund_deadline
        AND special_refund_deadline <= new_start_time
        AND special_refund_deadline <= TIMESTAMPADD(DAY, 7, announcement_time)),
    INDEX idx_reschedule_session (session_id, event_id),
    INDEX idx_reschedule_due (special_refund_deadline, event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE reschedule_affected_ticket (
    event_id BIGINT NOT NULL,
    ticket_id BIGINT NOT NULL,
    buyer_id BIGINT NOT NULL,
    order_id BIGINT NOT NULL,
    session_id BIGINT NOT NULL,
    seat_id BIGINT NOT NULL,
    original_paid_amount DECIMAL(10,2) NOT NULL,
    PRIMARY KEY (event_id, ticket_id),
    CONSTRAINT chk_reschedule_snapshot CHECK (event_id > 0 AND ticket_id > 0 AND buyer_id > 0
        AND order_id > 0 AND session_id > 0 AND seat_id > 0 AND original_paid_amount > 0),
    INDEX idx_reschedule_buyer (buyer_id, event_id, ticket_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE reschedule_response (
    event_id BIGINT NOT NULL,
    ticket_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    responded_at DATETIME(3) NULL,
    auto_retained_at DATETIME(3) NULL,
    source VARCHAR(24) NULL,
    PRIMARY KEY (event_id, ticket_id),
    CONSTRAINT chk_reschedule_response CHECK (
        (status = 'PENDING' AND responded_at IS NULL AND auto_retained_at IS NULL AND source IS NULL)
        OR (status = 'USER_CONFIRMED' AND responded_at IS NOT NULL AND auto_retained_at IS NULL AND source IS NOT NULL AND source = 'USER')
        OR (status = 'AUTO_RETAINED' AND responded_at IS NULL AND auto_retained_at IS NOT NULL
            AND source IS NOT NULL AND source IN ('MQ', 'DEADLINE_SCAN'))),
    INDEX idx_reschedule_pending (status, event_id, ticket_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE reschedule_refund_submission (
    submission_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    event_id BIGINT NOT NULL,
    buyer_id BIGINT NOT NULL,
    request_id VARCHAR(64) COLLATE utf8mb4_0900_bin NOT NULL,
    ticket_ids VARCHAR(2200) COLLATE utf8mb4_0900_bin NOT NULL,
    accepted_at DATETIME(3) NOT NULL,
    CONSTRAINT uq_reschedule_refund_submission UNIQUE (buyer_id, request_id),
    CONSTRAINT chk_reschedule_submission CHECK (event_id > 0 AND buyer_id > 0
        AND CHAR_LENGTH(TRIM(request_id)) > 0 AND CHAR_LENGTH(ticket_ids) > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE reschedule_refund_link (
    submission_id BIGINT NOT NULL,
    refund_id BIGINT NOT NULL,
    PRIMARY KEY (submission_id, refund_id),
    UNIQUE KEY uq_reschedule_link_refund (refund_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE ticket_refund_request
    ADD COLUMN refund_policy VARCHAR(32) NOT NULL DEFAULT 'NORMAL_REFUND',
    ADD COLUMN source_event_id BIGINT NULL,
    ADD COLUMN accepted_at DATETIME(3) NULL,
    ADD CONSTRAINT chk_refund_policy_source CHECK (
        (refund_policy = 'NORMAL_REFUND' AND source_event_id IS NULL)
        OR (refund_policy = 'SESSION_RESCHEDULE_REFUND' AND source_event_id IS NOT NULL AND source_event_id > 0
            AND accepted_at IS NOT NULL AND accepted_at = requested_at)),
    ADD INDEX idx_refund_source_event (source_event_id, buyer_id, refund_id);

ALTER TABLE outbox_event DROP CHECK chk_outbox_event_type,
    ADD CONSTRAINT chk_outbox_event_type CHECK (event_type IN ('ORDER_EXPIRED', 'PAYMENT_SUCCEEDED',
        'REFUND_SUCCEEDED', 'TICKET_QUALIFICATION_CHANGED', 'SESSION_RESCHEDULED', 'RESCHEDULE_WINDOW_CLOSED'));
