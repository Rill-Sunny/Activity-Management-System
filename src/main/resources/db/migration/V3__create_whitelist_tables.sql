-- A config row records explicit configuration, even when it has no members.
-- Logical references follow the project's no-physical-foreign-key model.
CREATE TABLE whitelist_config (
    config_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    scope_type VARCHAR(20) NOT NULL,
    scope_id BIGINT NOT NULL COMMENT 'Logical reference to activity or activity_session, selected by scope_type',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),

    CONSTRAINT uq_whitelist_config_scope UNIQUE (scope_type, scope_id),
    CONSTRAINT chk_whitelist_config_scope_type
        CHECK (scope_type IN ('ACTIVITY', 'SESSION')),
    CONSTRAINT chk_whitelist_config_scope_id CHECK (scope_id > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE whitelist_member (
    member_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    config_id BIGINT NOT NULL COMMENT 'Logical reference to whitelist_config.config_id',
    user_id BIGINT NOT NULL COMMENT 'Logical reference to app_user.id',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

    CONSTRAINT uq_whitelist_member_config_user UNIQUE (config_id, user_id),
    CONSTRAINT chk_whitelist_member_config_id CHECK (config_id > 0),
    CONSTRAINT chk_whitelist_member_user_id CHECK (user_id > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
