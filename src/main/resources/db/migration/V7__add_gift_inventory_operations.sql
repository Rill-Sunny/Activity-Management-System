-- GIFT resource history only. No recipients, paid Tickets or physical foreign keys.
-- ALLOCATE commits its operation and Seat details in the same transaction as resource changes.
CREATE TABLE gift_inventory_operation (
    operation_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    request_id VARCHAR(64) COLLATE utf8mb4_0900_bin NOT NULL,
    publisher_id BIGINT NOT NULL COMMENT 'Logical reference to app_user.id, the operating publisher',
    session_id BIGINT NOT NULL COMMENT 'Logical reference to activity_session.activity_session_id',
    zone_id BIGINT NOT NULL COMMENT 'Logical reference to a GIFT session_zone.zone_id',
    operation_type VARCHAR(16) COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'ALLOCATE',
    quantity INT NOT NULL,
    status VARCHAR(20) COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'SUCCEEDED',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT chk_gift_operation_ids CHECK (publisher_id > 0 AND session_id > 0 AND zone_id > 0),
    CONSTRAINT chk_gift_operation_request CHECK (CHAR_LENGTH(TRIM(request_id)) > 0),
    CONSTRAINT chk_gift_operation_type CHECK (operation_type = 'ALLOCATE'),
    CONSTRAINT chk_gift_operation_quantity CHECK (quantity > 0),
    CONSTRAINT chk_gift_operation_status CHECK (status = 'SUCCEEDED'),
    CONSTRAINT uq_gift_operation_request UNIQUE (publisher_id, request_id),
    INDEX idx_gift_operation_zone_history (zone_id, operation_id),
    INDEX idx_gift_operation_session_history (session_id, operation_id),
    INDEX idx_gift_operation_publisher_created (publisher_id, created_at, operation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE gift_inventory_operation_item (
    operation_item_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    operation_id BIGINT NOT NULL COMMENT 'Logical reference to gift_inventory_operation.operation_id',
    seat_id BIGINT NOT NULL COMMENT 'Logical reference to the GIFT session_seat.seat_id',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT chk_gift_operation_item_ids CHECK (operation_id > 0 AND seat_id > 0),
    CONSTRAINT uq_gift_operation_item_seat UNIQUE (operation_id, seat_id),
    INDEX idx_gift_operation_item_seat_history (seat_id, operation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
