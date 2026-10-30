-- Free reservations retain history. Only ACTIVE records participate in the account/session unique key.
-- Logical references follow the project's existing no-physical-foreign-key model.
CREATE TABLE free_reservation (
    reservation_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL COMMENT 'Logical reference to app_user.id',
    session_id BIGINT NOT NULL COMMENT 'Logical reference to activity_session.activity_session_id; FREE only',
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    cancelled_at DATETIME(3) NULL,

    active_session_id BIGINT GENERATED ALWAYS AS (
        CASE WHEN status = 'ACTIVE' THEN session_id ELSE NULL END
    ) VIRTUAL,

    CONSTRAINT chk_free_reservation_user_id CHECK (user_id > 0),
    CONSTRAINT chk_free_reservation_session_id CHECK (session_id > 0),
    CONSTRAINT chk_free_reservation_status CHECK (status IN ('ACTIVE', 'CANCELLED')),
    CONSTRAINT chk_free_reservation_status_time CHECK (
        (status = 'ACTIVE' AND cancelled_at IS NULL)
        OR (status = 'CANCELLED' AND cancelled_at IS NOT NULL)
    ),
    CONSTRAINT chk_free_reservation_cancelled_time CHECK (cancelled_at IS NULL OR cancelled_at >= created_at),
    CONSTRAINT uq_free_reservation_active UNIQUE (user_id, active_session_id),
    INDEX idx_free_reservation_user_history (user_id, created_at, reservation_id),
    INDEX idx_free_reservation_session_status (session_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- One runtime quota row per FREE session, initialized from its frozen capacity on first reservation.
-- Initialization and all reservation/cancellation writes belong to the same Service transaction.
CREATE TABLE free_session_quota (
    session_id BIGINT PRIMARY KEY COMMENT 'Logical reference to activity_session.activity_session_id; FREE only',
    capacity INT NOT NULL COMMENT 'Snapshot of the published FREE session capacity',
    remaining INT NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),

    CONSTRAINT chk_free_quota_session_id CHECK (session_id > 0),
    CONSTRAINT chk_free_quota_capacity CHECK (capacity > 0),
    CONSTRAINT chk_free_quota_remaining CHECK (remaining >= 0 AND remaining <= capacity)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
