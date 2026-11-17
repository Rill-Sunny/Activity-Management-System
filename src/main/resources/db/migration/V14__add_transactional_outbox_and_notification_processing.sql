-- Asynchronous event delivery and consumption are coordinated with business writes.
-- All references remain logical; this project intentionally has no physical foreign keys.
CREATE TABLE outbox_event (
    event_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL PRIMARY KEY,
    event_type VARCHAR(40) COLLATE utf8mb4_0900_bin NOT NULL,
    aggregate_id BIGINT NOT NULL,
    payload JSON NOT NULL,
    status VARCHAR(16) COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'PENDING',
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    lease_until DATETIME(3) NULL,
    lease_token CHAR(36) COLLATE utf8mb4_0900_bin NULL,
    traceparent VARCHAR(128) COLLATE utf8mb4_0900_bin NULL,
    failure_reason VARCHAR(1000) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    published_at DATETIME(3) NULL,
    CONSTRAINT chk_outbox_aggregate_id CHECK (aggregate_id > 0),
    CONSTRAINT chk_outbox_event_type CHECK (event_type IN ('ORDER_EXPIRED', 'PAYMENT_SUCCEEDED', 'REFUND_SUCCEEDED')),
    CONSTRAINT chk_outbox_status CHECK (status IN ('PENDING', 'CLAIMED', 'SENT', 'FAILED')),
    CONSTRAINT chk_outbox_attempts CHECK (attempts >= 0),
    CONSTRAINT chk_outbox_lease CHECK (
        (status = 'CLAIMED' AND lease_until IS NOT NULL AND lease_token IS NOT NULL)
        OR (status <> 'CLAIMED' AND lease_until IS NULL AND lease_token IS NULL)
    ),
    CONSTRAINT chk_outbox_published CHECK (
        (status = 'SENT' AND published_at IS NOT NULL)
        OR (status <> 'SENT' AND published_at IS NULL)
    ),
    INDEX idx_outbox_due (status, next_attempt_at, created_at, event_id),
    INDEX idx_outbox_expired_lease (status, lease_until, created_at, event_id),
    INDEX idx_outbox_aggregate (event_type, aggregate_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE consumer_process (
    consumer_name VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
    event_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
    processed_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT pk_consumer_process PRIMARY KEY (consumer_name, event_id),
    INDEX idx_consumer_process_event (event_id, consumer_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE consumer_failure (
    consumer_name VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
    event_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
    attempts INT NOT NULL DEFAULT 1,
    status VARCHAR(16) COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'RETRYING',
    last_error VARCHAR(1000) NOT NULL,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT pk_consumer_failure PRIMARY KEY (consumer_name, event_id),
    CONSTRAINT chk_consumer_failure_attempts CHECK (attempts >= 1),
    CONSTRAINT chk_consumer_failure_status CHECK (status IN ('RETRYING', 'FAILED')),
    INDEX idx_consumer_failure_status (status, updated_at, consumer_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE notification (
    notification_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL COMMENT 'Logical reference to app_user.id',
    event_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
    content VARCHAR(500) NOT NULL,
    status VARCHAR(16) COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'UNREAD',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    read_at DATETIME(3) NULL,
    CONSTRAINT chk_notification_user CHECK (user_id > 0),
    CONSTRAINT chk_notification_content CHECK (CHAR_LENGTH(TRIM(content)) > 0),
    CONSTRAINT chk_notification_status CHECK (status IN ('UNREAD', 'READ')),
    CONSTRAINT chk_notification_read_time CHECK (
        (status = 'UNREAD' AND read_at IS NULL)
        OR (status = 'READ' AND read_at IS NOT NULL)
    ),
    CONSTRAINT uq_notification_user_event UNIQUE (user_id, event_id),
    INDEX idx_notification_user_feed (user_id, created_at, notification_id),
    INDEX idx_notification_user_unread (user_id, status, created_at, notification_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
