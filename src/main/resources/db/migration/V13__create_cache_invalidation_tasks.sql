CREATE TABLE cache_generation (
    cache_identity VARCHAR(191) COLLATE utf8mb4_0900_bin NOT NULL PRIMARY KEY,
    generation BIGINT NOT NULL DEFAULT 0,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT chk_cache_generation_nonnegative CHECK (generation >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE cache_invalidation_task (
    task_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    cache_identity VARCHAR(191) COLLATE utf8mb4_0900_bin NOT NULL,
    generation BIGINT NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    next_attempt_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    attempts INT NOT NULL DEFAULT 0,
    completed_at DATETIME(3) NULL,
    last_error VARCHAR(255) NULL,
    CONSTRAINT chk_cache_invalidation_generation CHECK (generation > 0),
    CONSTRAINT chk_cache_invalidation_attempts CHECK (attempts >= 0),
    INDEX idx_cache_invalidation_pending (completed_at, next_attempt_at, task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
