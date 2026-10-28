CREATE TABLE app_user (
                          id BIGINT PRIMARY KEY AUTO_INCREMENT,
                          username VARCHAR(50) NOT NULL,
                          password_hash VARCHAR(255) NOT NULL COMMENT 'Stores a secure password hash; plaintext is forbidden',
                          role VARCHAR(20) NOT NULL DEFAULT 'USER',
                          status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
                          created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

                          CONSTRAINT uq_app_user_username UNIQUE (username),
                          CONSTRAINT chk_app_user_username
                              CHECK (CHAR_LENGTH(TRIM(username)) > 0),
                          CONSTRAINT chk_app_user_password_hash
                              CHECK (CHAR_LENGTH(TRIM(password_hash)) > 0),
                          CONSTRAINT chk_app_user_role
                              CHECK (role IN ('USER', 'PUBLISHER')),
                          CONSTRAINT chk_app_user_status
                              CHECK (status IN ('ACTIVE', 'DISABLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE activity (
                          activity_id BIGINT PRIMARY KEY AUTO_INCREMENT,
                          publisher_id BIGINT NOT NULL COMMENT 'Logical reference to app_user.id',
                          name VARCHAR(50) NOT NULL,
                          description VARCHAR(1000) NULL,
                          type VARCHAR(5) NOT NULL,
                          status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
                          open_time DATETIME(3) NOT NULL,
                          close_time DATETIME(3) NOT NULL,

                          CONSTRAINT chk_activity_type
                              CHECK (type IN ('FREE', 'PAID')),
                          CONSTRAINT chk_activity_status
                              CHECK (status IN ('DRAFT', 'PUBLISHED', 'FINISHED', 'CANCELLED')),
                          CONSTRAINT chk_activity_open_window
                              CHECK (open_time < close_time),

                          INDEX idx_activity_publisher_status (publisher_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE activity_session (
                                  activity_session_id BIGINT PRIMARY KEY AUTO_INCREMENT,
                                  activity_id BIGINT NOT NULL COMMENT 'Logical reference to activity.activity_id',
                                  start_time DATETIME(3) NOT NULL,
                                  end_time DATETIME(3) NOT NULL,
                                  capacity INT NOT NULL,
                                  status VARCHAR(20) NOT NULL DEFAULT 'SCHEDULED',
                                  max_tickets_per_user INT NULL COMMENT 'Per-account ticket purchase limit for paid sessions; NULL for free sessions',
                                  seat_mode VARCHAR(20) NULL COMMENT 'Seat selection mode for paid sessions; NULL for free sessions',

                                  CONSTRAINT chk_session_time
                                      CHECK (start_time < end_time),
                                  CONSTRAINT chk_session_capacity
                                      CHECK (capacity > 0),
                                  CONSTRAINT chk_session_status
                                      CHECK (status IN ('SCHEDULED', 'FINISHED', 'CANCELLED')),
                                  CONSTRAINT chk_session_purchase_limit
                                      CHECK (max_tickets_per_user IS NULL
                                          OR (max_tickets_per_user > 0 AND max_tickets_per_user <= capacity)),
                                  CONSTRAINT chk_session_seat_mode
                                      CHECK (seat_mode IS NULL
                                          OR seat_mode IN ('SELECT_SEAT', 'AUTO_ASSIGN')),

                                  INDEX idx_session_activity_time (activity_id, start_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE session_zone (
                              zone_id BIGINT PRIMARY KEY AUTO_INCREMENT,
                              session_id BIGINT NOT NULL COMMENT 'Logical reference to activity_session.activity_session_id',
                              name VARCHAR(100) NOT NULL,
                              zone_type VARCHAR(10) NOT NULL,
                              price DECIMAL(10,2) NOT NULL,
                              total_amount INT NOT NULL,
                              remains_amount INT NOT NULL,

                              CONSTRAINT chk_zone_type
                                  CHECK (zone_type IN ('PAID', 'GIFT')),
                              CONSTRAINT chk_zone_price
                                  CHECK (
                                      (zone_type = 'PAID' AND price > 0)
                                          OR (zone_type = 'GIFT' AND price = 0)
                                      ),
                              CONSTRAINT chk_zone_inventory
                                  CHECK (
                                      total_amount > 0
                                          AND remains_amount >= 0
                                          AND remains_amount <= total_amount
                                      ),
                              CONSTRAINT uq_zone_session_name
                                  UNIQUE (session_id, name),

                              INDEX idx_zone_session_type (session_id, zone_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE session_seat (
                              seat_id BIGINT PRIMARY KEY AUTO_INCREMENT,
                              zone_id BIGINT NOT NULL COMMENT 'Logical reference to session_zone.zone_id',
                              row_no INT NOT NULL,
                              seat_no INT NOT NULL,
                              allocation_priority INT NOT NULL,
                              allocation_status VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE',

                              CONSTRAINT chk_seat_row CHECK (row_no > 0),
                              CONSTRAINT chk_seat_number CHECK (seat_no > 0),
                              CONSTRAINT chk_seat_priority CHECK (allocation_priority > 0),
                              CONSTRAINT chk_seat_allocation_status
                                  CHECK (allocation_status IN ('AVAILABLE', 'SOLD', 'GIFT_ALLOCATED')),
                              CONSTRAINT uq_seat_zone_position
                                  UNIQUE (zone_id, row_no, seat_no),

                              INDEX idx_seat_zone_availability_priority
                                  (zone_id, allocation_status, allocation_priority, seat_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ticket_order (
                              order_id BIGINT PRIMARY KEY AUTO_INCREMENT,
                              buyer_id BIGINT NOT NULL COMMENT 'Logical reference to app_user.id',
                              session_id BIGINT NOT NULL COMMENT 'Logical reference to activity_session.activity_session_id',
                              request_id VARCHAR(64) NOT NULL COMMENT 'Idempotency key for order creation requests by the same user',

                              total_amount DECIMAL(12,2) NOT NULL,
                              status VARCHAR(30) NOT NULL DEFAULT 'PENDING_PAYMENT',

                              created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                              expires_at DATETIME(3)
        GENERATED ALWAYS AS (DATE_ADD(created_at, INTERVAL 15 MINUTE)) STORED,
                              paid_at DATETIME(3) NULL,
                              closed_reason VARCHAR(100) NULL,

                              CONSTRAINT chk_ticket_order_amount CHECK (total_amount > 0),
                              CONSTRAINT chk_ticket_order_request_id
                                  CHECK (CHAR_LENGTH(TRIM(request_id)) > 0),
                              CONSTRAINT chk_ticket_order_status
                                  CHECK (status IN (
                                                    'PENDING_PAYMENT', 'PAID',
                                                    'PARTIALLY_REFUNDED', 'REFUNDED', 'CLOSED'
                                      )),
                              CONSTRAINT chk_ticket_order_status_fields
                                  CHECK (
                                      (status = 'PENDING_PAYMENT'
                                          AND paid_at IS NULL AND closed_reason IS NULL)
                                          OR
                                      (status = 'CLOSED'
                                          AND paid_at IS NULL AND closed_reason IS NOT NULL)
                                          OR
                                      (status IN ('PAID', 'PARTIALLY_REFUNDED', 'REFUNDED')
                                          AND paid_at IS NOT NULL AND closed_reason IS NULL)
                                      ),
                              CONSTRAINT chk_ticket_order_paid_time
                                  CHECK (paid_at IS NULL
                                      OR (paid_at >= created_at AND paid_at < expires_at)),

                              CONSTRAINT uq_ticket_order_request UNIQUE (buyer_id, request_id),
                              INDEX idx_ticket_order_buyer_created (buyer_id, created_at),
                              INDEX idx_ticket_order_session_status (session_id, status),
                              INDEX idx_ticket_order_expiry (status, expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ticket_order_item (
                                   order_item_id BIGINT PRIMARY KEY AUTO_INCREMENT,
                                   order_id BIGINT NOT NULL COMMENT 'Logical reference to ticket_order.order_id',
                                   zone_id BIGINT NOT NULL COMMENT 'Logical reference to session_zone.zone_id',

                                   allocation_mode VARCHAR(20) NOT NULL,
                                   requested_seat_id BIGINT NULL COMMENT 'Specified for user-selected seating; NULL for automatic seat assignment',
                                   quantity INT NOT NULL,
                                   unit_price DECIMAL(10,2) NOT NULL COMMENT 'Snapshot of the per-ticket price for the zone, confirmed at order creation',
                                   accept_non_adjacent TINYINT NOT NULL DEFAULT 0,

                                   CONSTRAINT chk_ticket_item_quantity CHECK (quantity > 0),
                                   CONSTRAINT chk_ticket_item_unit_price CHECK (unit_price > 0),
                                   CONSTRAINT chk_ticket_item_mode
                                       CHECK (allocation_mode IN ('SELECT_SEAT', 'AUTO_ASSIGN')),
                                   CONSTRAINT chk_ticket_item_mode_fields
                                       CHECK (
                                           (allocation_mode = 'SELECT_SEAT'
                                               AND requested_seat_id IS NOT NULL AND quantity = 1
                                               AND accept_non_adjacent = 0)
                                               OR
                                           (allocation_mode = 'AUTO_ASSIGN'
                                               AND requested_seat_id IS NULL)
                                           ),
                                   CONSTRAINT chk_ticket_item_non_adjacent
                                       CHECK (accept_non_adjacent IN (0, 1)),

                                   CONSTRAINT uq_ticket_item_requested_seat
                                       UNIQUE (order_id, requested_seat_id),
                                   INDEX idx_ticket_item_zone (zone_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ticket (
                        ticket_id BIGINT PRIMARY KEY AUTO_INCREMENT,
                        order_item_id BIGINT NOT NULL COMMENT 'Logical reference to ticket_order_item.order_item_id',
                        seat_id BIGINT NOT NULL COMMENT 'Logical reference to session_seat.seat_id',

                        paid_price DECIMAL(10,2) NOT NULL COMMENT 'Snapshot of the actual transaction price for this ticket',
                        status VARCHAR(20) NOT NULL DEFAULT 'VALID',
                        issued_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                        refunded_at DATETIME(3) NULL,

                        active_seat_id BIGINT GENERATED ALWAYS AS (
                            CASE WHEN status = 'VALID' THEN seat_id ELSE NULL END
                            ) VIRTUAL,

                        CONSTRAINT chk_ticket_paid_price CHECK (paid_price > 0),
                        CONSTRAINT chk_ticket_status
                            CHECK (status IN ('VALID', 'REFUNDED')),
                        CONSTRAINT chk_ticket_status_time
                            CHECK (
                                (status = 'VALID' AND refunded_at IS NULL)
                                    OR (status = 'REFUNDED' AND refunded_at IS NOT NULL)
                                ),
                        CONSTRAINT chk_ticket_refunded_time
                            CHECK (refunded_at IS NULL OR refunded_at >= issued_at),

                        CONSTRAINT uq_ticket_active_seat UNIQUE (active_seat_id),
                        INDEX idx_ticket_item (order_item_id),
                        INDEX idx_ticket_seat_history (seat_id, issued_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE user_session_purchase (
                                       user_id BIGINT NOT NULL COMMENT 'Logical reference to app_user.id',
                                       session_id BIGINT NOT NULL COMMENT 'Logical reference to activity_session.activity_session_id',
                                       valid_count INT NOT NULL DEFAULT 0 COMMENT 'Current number of valid paid tickets that have not been refunded',
                                       updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),

                                       PRIMARY KEY (user_id, session_id),
                                       CONSTRAINT chk_user_session_purchase_count CHECK (valid_count >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;