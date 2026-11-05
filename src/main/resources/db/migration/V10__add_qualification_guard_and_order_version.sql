CREATE TABLE user_activity_qualification_guard (
    user_id BIGINT NOT NULL,
    activity_id BIGINT NOT NULL,
    qualification_version BIGINT NOT NULL DEFAULT 0,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT pk_user_activity_qualification_guard PRIMARY KEY (user_id, activity_id),
    CONSTRAINT chk_user_activity_qualification_guard_ids CHECK (user_id > 0 AND activity_id > 0),
    CONSTRAINT chk_user_activity_qualification_guard_version CHECK (qualification_version >= 0),
    INDEX idx_qualification_guard_activity_user (activity_id, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE product_order_item
    ADD COLUMN qualification_guard_version_snapshot BIGINT NULL,
    ADD CONSTRAINT chk_product_order_item_guard_version CHECK (
        (qualification_type_snapshot = 'TICKET_REQUIRED'
            AND (qualification_guard_version_snapshot IS NULL OR qualification_guard_version_snapshot >= 0))
        OR (qualification_type_snapshot <> 'TICKET_REQUIRED'
            AND qualification_guard_version_snapshot IS NULL)
    );
