-- Generated from V1, V3 through V18 in an isolated test database. No seed data.

CREATE TABLE `activity` (
  `activity_id` bigint NOT NULL AUTO_INCREMENT,
  `publisher_id` bigint NOT NULL COMMENT 'Logical reference to app_user.id',
  `name` varchar(50) NOT NULL,
  `description` varchar(1000) DEFAULT NULL,
  `type` varchar(5) NOT NULL,
  `status` varchar(20) NOT NULL DEFAULT 'DRAFT',
  `open_time` datetime(3) NOT NULL,
  `close_time` datetime(3) NOT NULL,
  PRIMARY KEY (`activity_id`),
  KEY `idx_activity_publisher_status` (`publisher_id`,`status`),
  CONSTRAINT `chk_activity_open_window` CHECK ((`open_time` < `close_time`)),
  CONSTRAINT `chk_activity_status` CHECK ((`status` in (_utf8mb4'DRAFT',_utf8mb4'PUBLISHED',_utf8mb4'FINISHED',_utf8mb4'CANCELLED'))),
  CONSTRAINT `chk_activity_type` CHECK ((`type` in (_utf8mb4'FREE',_utf8mb4'PAID')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `activity_session` (
  `activity_session_id` bigint NOT NULL AUTO_INCREMENT,
  `activity_id` bigint NOT NULL COMMENT 'Logical reference to activity.activity_id',
  `start_time` datetime(3) NOT NULL,
  `end_time` datetime(3) NOT NULL,
  `capacity` int NOT NULL,
  `status` varchar(20) NOT NULL DEFAULT 'SCHEDULED',
  `max_tickets_per_user` int DEFAULT NULL COMMENT 'Per-account ticket purchase limit for paid sessions; NULL for free sessions',
  `seat_mode` varchar(20) DEFAULT NULL COMMENT 'Seat selection mode for paid sessions; NULL for free sessions',
  PRIMARY KEY (`activity_session_id`),
  KEY `idx_session_activity_time` (`activity_id`,`start_time`),
  CONSTRAINT `chk_session_capacity` CHECK ((`capacity` > 0)),
  CONSTRAINT `chk_session_purchase_limit` CHECK (((`max_tickets_per_user` is null) or ((`max_tickets_per_user` > 0) and (`max_tickets_per_user` <= `capacity`)))),
  CONSTRAINT `chk_session_seat_mode` CHECK (((`seat_mode` is null) or (`seat_mode` in (_utf8mb4'SELECT_SEAT',_utf8mb4'AUTO_ASSIGN')))),
  CONSTRAINT `chk_session_status` CHECK ((`status` in (_utf8mb4'SCHEDULED',_utf8mb4'FINISHED',_utf8mb4'CANCELLED'))),
  CONSTRAINT `chk_session_time` CHECK ((`start_time` < `end_time`))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `app_user` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `username` varchar(50) NOT NULL,
  `password_hash` varchar(255) NOT NULL COMMENT 'Stores a secure password hash; plaintext is forbidden',
  `role` varchar(20) NOT NULL DEFAULT 'USER',
  `status` varchar(20) NOT NULL DEFAULT 'ACTIVE',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_app_user_username` (`username`),
  CONSTRAINT `chk_app_user_password_hash` CHECK ((char_length(trim(`password_hash`)) > 0)),
  CONSTRAINT `chk_app_user_role` CHECK ((`role` in (_utf8mb4'USER',_utf8mb4'PUBLISHER',_utf8mb4'ADMIN'))),
  CONSTRAINT `chk_app_user_status` CHECK ((`status` in (_utf8mb4'ACTIVE',_utf8mb4'DISABLED'))),
  CONSTRAINT `chk_app_user_username` CHECK ((char_length(trim(`username`)) > 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `cache_generation` (
  `cache_identity` varchar(191) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `generation` bigint NOT NULL DEFAULT '0',
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`cache_identity`),
  CONSTRAINT `chk_cache_generation_nonnegative` CHECK ((`generation` >= 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `cache_invalidation_task` (
  `task_id` bigint NOT NULL AUTO_INCREMENT,
  `cache_identity` varchar(191) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `generation` bigint NOT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `next_attempt_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `attempts` int NOT NULL DEFAULT '0',
  `completed_at` datetime(3) DEFAULT NULL,
  `last_error` varchar(255) DEFAULT NULL,
  PRIMARY KEY (`task_id`),
  KEY `idx_cache_invalidation_pending` (`completed_at`,`next_attempt_at`,`task_id`),
  CONSTRAINT `chk_cache_invalidation_attempts` CHECK ((`attempts` >= 0)),
  CONSTRAINT `chk_cache_invalidation_generation` CHECK ((`generation` > 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `cancellation_batch` (
  `batch_id` bigint NOT NULL AUTO_INCREMENT,
  `event_id` bigint NOT NULL,
  `status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'PENDING',
  `scan_phase` int NOT NULL DEFAULT '0',
  `next_cursor` bigint NOT NULL DEFAULT '0',
  `total_items` int NOT NULL DEFAULT '0',
  `succeeded_items` int NOT NULL DEFAULT '0',
  `failed_items` int NOT NULL DEFAULT '0',
  `started_at` datetime(3) DEFAULT NULL,
  `completed_at` datetime(3) DEFAULT NULL,
  PRIMARY KEY (`batch_id`),
  UNIQUE KEY `uq_cancellation_batch_event` (`event_id`),
  CONSTRAINT `chk_cancellation_batch` CHECK (((`status` in (_utf8mb4'PENDING',_utf8mb4'PROCESSING',_utf8mb4'COMPLETED',_utf8mb4'PARTIAL_FAILED')) and (`scan_phase` between 0 and 4) and (`next_cursor` >= 0) and (`total_items` >= 0) and (`succeeded_items` >= 0) and (`failed_items` >= 0) and ((`succeeded_items` + `failed_items`) <= `total_items`) and ((`status` <> _utf8mb4'COMPLETED') or ((`scan_phase` = 4) and (`succeeded_items` = `total_items`) and (`completed_at` is not null)))))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `cancellation_batch_item` (
  `item_id` bigint NOT NULL AUTO_INCREMENT,
  `batch_id` bigint NOT NULL,
  `target_type` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `target_id` bigint NOT NULL,
  `session_id` bigint NOT NULL,
  `action` varchar(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'PENDING',
  `attempt_count` int NOT NULL DEFAULT '0',
  `retry_count` int NOT NULL DEFAULT '0',
  `next_attempt_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `lease_until` datetime(3) DEFAULT NULL,
  `claim_token` varchar(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin DEFAULT NULL,
  `failure_reason` varchar(1000) DEFAULT NULL,
  `processed_at` datetime(3) DEFAULT NULL,
  `refund_id` bigint DEFAULT NULL,
  PRIMARY KEY (`item_id`),
  UNIQUE KEY `uq_cancellation_item` (`batch_id`,`target_type`,`target_id`,`action`),
  KEY `idx_cancellation_work` (`status`,`next_attempt_at`,`item_id`),
  KEY `idx_cancellation_lease` (`status`,`lease_until`,`item_id`),
  CONSTRAINT `chk_cancellation_item` CHECK (((`batch_id` > 0) and (`target_id` > 0) and (`session_id` > 0) and (`attempt_count` >= 0) and (`retry_count` >= 0) and (((`target_type` = _utf8mb4'ORDER') and (`action` = _utf8mb4'CLOSE')) or ((`target_type` = _utf8mb4'TICKET') and (`action` = _utf8mb4'REFUND')) or ((`target_type` in (_utf8mb4'RESERVATION',_utf8mb4'GIFT')) and (`action` = _utf8mb4'INVALIDATE'))) and (`status` in (_utf8mb4'PENDING',_utf8mb4'PROCESSING',_utf8mb4'RETRY_PENDING',_utf8mb4'FAILED',_utf8mb4'SUCCEEDED')) and (((`status` = _utf8mb4'PROCESSING') and (`lease_until` is not null) and (`claim_token` is not null)) or ((`status` <> _utf8mb4'PROCESSING') and (`lease_until` is null) and (`claim_token` is null))) and ((`status` <> _utf8mb4'SUCCEEDED') or (`processed_at` is not null)) and ((`status` not in (_utf8mb4'FAILED',_utf8mb4'RETRY_PENDING')) or (`failure_reason` is not null))))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `cancellation_event` (
  `event_id` bigint NOT NULL AUTO_INCREMENT,
  `cancel_type` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `target_id` bigint NOT NULL,
  `activity_id` bigint NOT NULL,
  `publisher_id` bigint NOT NULL,
  `operator_id` bigint NOT NULL,
  `reason` varchar(500) NOT NULL,
  `cancelled_at` datetime(3) NOT NULL,
  `request_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`event_id`),
  UNIQUE KEY `uq_cancellation_target` (`cancel_type`,`target_id`),
  UNIQUE KEY `uq_cancellation_request` (`publisher_id`,`request_id`),
  CONSTRAINT `chk_cancellation_event` CHECK (((`cancel_type` in (_utf8mb4'SESSION',_utf8mb4'ACTIVITY')) and (`target_id` > 0) and (`activity_id` > 0) and (`publisher_id` > 0) and (`operator_id` = `publisher_id`) and (char_length(trim(`reason`)) > 0) and (char_length(trim(`request_id`)) > 0)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `cancellation_scope` (
  `event_id` bigint NOT NULL,
  `session_id` bigint NOT NULL,
  PRIMARY KEY (`event_id`,`session_id`),
  UNIQUE KEY `uq_cancellation_session` (`session_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `consumer_failure` (
  `consumer_name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `event_id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `attempts` int NOT NULL DEFAULT '1',
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'RETRYING',
  `last_error` varchar(1000) NOT NULL,
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`consumer_name`,`event_id`),
  KEY `idx_consumer_failure_status` (`status`,`updated_at`,`consumer_name`),
  CONSTRAINT `chk_consumer_failure_attempts` CHECK ((`attempts` >= 1)),
  CONSTRAINT `chk_consumer_failure_status` CHECK ((`status` in (_utf8mb4'RETRYING',_utf8mb4'FAILED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `consumer_process` (
  `consumer_name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `event_id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `processed_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`consumer_name`,`event_id`),
  KEY `idx_consumer_process_event` (`event_id`,`consumer_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `free_reservation` (
  `reservation_id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL COMMENT 'Logical reference to app_user.id',
  `session_id` bigint NOT NULL COMMENT 'Logical reference to activity_session.activity_session_id; FREE only',
  `status` varchar(20) NOT NULL DEFAULT 'ACTIVE',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `cancelled_at` datetime(3) DEFAULT NULL,
  `active_session_id` bigint GENERATED ALWAYS AS ((case when (`status` = _utf8mb4'ACTIVE') then `session_id` else NULL end)) VIRTUAL,
  PRIMARY KEY (`reservation_id`),
  UNIQUE KEY `uq_free_reservation_active` (`user_id`,`active_session_id`),
  KEY `idx_free_reservation_user_history` (`user_id`,`created_at`,`reservation_id`),
  KEY `idx_free_reservation_session_status` (`session_id`,`status`),
  CONSTRAINT `chk_free_reservation_cancelled_time` CHECK (((`cancelled_at` is null) or (`cancelled_at` >= `created_at`))),
  CONSTRAINT `chk_free_reservation_session_id` CHECK ((`session_id` > 0)),
  CONSTRAINT `chk_free_reservation_status` CHECK ((`status` in (_utf8mb4'ACTIVE',_utf8mb4'CANCELLED'))),
  CONSTRAINT `chk_free_reservation_status_time` CHECK ((((`status` = _utf8mb4'ACTIVE') and (`cancelled_at` is null)) or ((`status` = _utf8mb4'CANCELLED') and (`cancelled_at` is not null)))),
  CONSTRAINT `chk_free_reservation_user_id` CHECK ((`user_id` > 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `free_session_quota` (
  `session_id` bigint NOT NULL COMMENT 'Logical reference to activity_session.activity_session_id; FREE only',
  `capacity` int NOT NULL COMMENT 'Snapshot of the published FREE session capacity',
  `remaining` int NOT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`session_id`),
  CONSTRAINT `chk_free_quota_capacity` CHECK ((`capacity` > 0)),
  CONSTRAINT `chk_free_quota_remaining` CHECK (((`remaining` >= 0) and (`remaining` <= `capacity`))),
  CONSTRAINT `chk_free_quota_session_id` CHECK ((`session_id` > 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `gift_inventory_operation` (
  `operation_id` bigint NOT NULL AUTO_INCREMENT,
  `request_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `publisher_id` bigint NOT NULL COMMENT 'Logical reference to app_user.id, the operating publisher',
  `session_id` bigint NOT NULL COMMENT 'Logical reference to activity_session.activity_session_id',
  `zone_id` bigint NOT NULL COMMENT 'Logical reference to a GIFT session_zone.zone_id',
  `operation_type` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'ALLOCATE',
  `quantity` int NOT NULL,
  `status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'SUCCEEDED',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`operation_id`),
  UNIQUE KEY `uq_gift_operation_request` (`publisher_id`,`request_id`),
  KEY `idx_gift_operation_zone_history` (`zone_id`,`operation_id`),
  KEY `idx_gift_operation_session_history` (`session_id`,`operation_id`),
  KEY `idx_gift_operation_publisher_created` (`publisher_id`,`created_at`,`operation_id`),
  CONSTRAINT `chk_gift_operation_ids` CHECK (((`publisher_id` > 0) and (`session_id` > 0) and (`zone_id` > 0))),
  CONSTRAINT `chk_gift_operation_quantity` CHECK ((`quantity` > 0)),
  CONSTRAINT `chk_gift_operation_request` CHECK ((char_length(trim(`request_id`)) > 0)),
  CONSTRAINT `chk_gift_operation_status` CHECK ((`status` = _utf8mb4'SUCCEEDED')),
  CONSTRAINT `chk_gift_operation_type` CHECK ((`operation_type` = _utf8mb4'ALLOCATE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `gift_inventory_operation_item` (
  `operation_item_id` bigint NOT NULL AUTO_INCREMENT,
  `operation_id` bigint NOT NULL COMMENT 'Logical reference to gift_inventory_operation.operation_id',
  `seat_id` bigint NOT NULL COMMENT 'Logical reference to the GIFT session_seat.seat_id',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `cancelled_at` datetime(3) DEFAULT NULL,
  `cancellation_event_id` bigint DEFAULT NULL,
  PRIMARY KEY (`operation_item_id`),
  UNIQUE KEY `uq_gift_operation_item_seat` (`operation_id`,`seat_id`),
  KEY `idx_gift_operation_item_seat_history` (`seat_id`,`operation_id`),
  CONSTRAINT `chk_gift_cancellation` CHECK ((((`cancelled_at` is null) and (`cancellation_event_id` is null)) or ((`cancelled_at` is not null) and (`cancellation_event_id` is not null) and (`cancellation_event_id` > 0)))),
  CONSTRAINT `chk_gift_operation_item_ids` CHECK (((`operation_id` > 0) and (`seat_id` > 0)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `notification` (
  `notification_id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL COMMENT 'Logical reference to app_user.id',
  `event_id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `content` varchar(500) NOT NULL,
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'UNREAD',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `read_at` datetime(3) DEFAULT NULL,
  PRIMARY KEY (`notification_id`),
  UNIQUE KEY `uq_notification_user_event` (`user_id`,`event_id`),
  KEY `idx_notification_user_feed` (`user_id`,`created_at`,`notification_id`),
  KEY `idx_notification_user_unread` (`user_id`,`status`,`created_at`,`notification_id`),
  CONSTRAINT `chk_notification_content` CHECK ((char_length(trim(`content`)) > 0)),
  CONSTRAINT `chk_notification_read_time` CHECK ((((`status` = _utf8mb4'UNREAD') and (`read_at` is null)) or ((`status` = _utf8mb4'READ') and (`read_at` is not null)))),
  CONSTRAINT `chk_notification_status` CHECK ((`status` in (_utf8mb4'UNREAD',_utf8mb4'READ'))),
  CONSTRAINT `chk_notification_user` CHECK ((`user_id` > 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `outbox_event` (
  `event_id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `event_type` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `aggregate_id` bigint NOT NULL,
  `payload` json NOT NULL,
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'PENDING',
  `attempts` int NOT NULL DEFAULT '0',
  `next_attempt_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `lease_until` datetime(3) DEFAULT NULL,
  `lease_token` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin DEFAULT NULL,
  `traceparent` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin DEFAULT NULL,
  `failure_reason` varchar(1000) DEFAULT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `published_at` datetime(3) DEFAULT NULL,
  PRIMARY KEY (`event_id`),
  KEY `idx_outbox_due` (`status`,`next_attempt_at`,`created_at`,`event_id`),
  KEY `idx_outbox_expired_lease` (`status`,`lease_until`,`created_at`,`event_id`),
  KEY `idx_outbox_aggregate` (`event_type`,`aggregate_id`,`created_at`),
  CONSTRAINT `chk_outbox_aggregate_id` CHECK ((`aggregate_id` > 0)),
  CONSTRAINT `chk_outbox_attempts` CHECK ((`attempts` >= 0)),
  CONSTRAINT `chk_outbox_event_type` CHECK ((`event_type` in (_utf8mb4'ORDER_EXPIRED',_utf8mb4'PAYMENT_SUCCEEDED',_utf8mb4'REFUND_SUCCEEDED',_utf8mb4'TICKET_QUALIFICATION_CHANGED',_utf8mb4'SESSION_RESCHEDULED',_utf8mb4'RESCHEDULE_WINDOW_CLOSED',_utf8mb4'SESSION_CANCELLED',_utf8mb4'ACTIVITY_CANCELLED'))),
  CONSTRAINT `chk_outbox_lease` CHECK ((((`status` = _utf8mb4'CLAIMED') and (`lease_until` is not null) and (`lease_token` is not null)) or ((`status` <> _utf8mb4'CLAIMED') and (`lease_until` is null) and (`lease_token` is null)))),
  CONSTRAINT `chk_outbox_published` CHECK ((((`status` = _utf8mb4'SENT') and (`published_at` is not null)) or ((`status` <> _utf8mb4'SENT') and (`published_at` is null)))),
  CONSTRAINT `chk_outbox_status` CHECK ((`status` in (_utf8mb4'PENDING',_utf8mb4'CLAIMED',_utf8mb4'SENT',_utf8mb4'FAILED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `product` (
  `product_id` bigint NOT NULL AUTO_INCREMENT,
  `publisher_id` bigint NOT NULL,
  `activity_id` bigint NOT NULL,
  `name` varchar(100) NOT NULL,
  `description` varchar(1000) DEFAULT NULL,
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'DRAFT',
  `qualification_type` varchar(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `qualification_scope` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin DEFAULT NULL,
  `qualification_session_id` bigint DEFAULT NULL,
  `refund_policy_template` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `refund_policy_version` smallint NOT NULL DEFAULT '1',
  `purchase_limit` int NOT NULL,
  `first_on_sale_at` datetime(3) DEFAULT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`product_id`),
  KEY `idx_product_publisher_status` (`publisher_id`,`status`,`product_id`),
  KEY `idx_product_activity_status` (`activity_id`,`status`,`product_id`),
  KEY `idx_product_qualification_session` (`qualification_session_id`,`product_id`),
  CONSTRAINT `chk_product_ids` CHECK (((`publisher_id` > 0) and (`activity_id` > 0))),
  CONSTRAINT `chk_product_limit` CHECK ((`purchase_limit` > 0)),
  CONSTRAINT `chk_product_name` CHECK ((char_length(trim(`name`)) > 0)),
  CONSTRAINT `chk_product_policy` CHECK (((`refund_policy_template` in (_utf8mb4'STANDARD_7D',_utf8mb4'SPECIAL_NO_REASON_RETURN')) and (`refund_policy_version` = 1))),
  CONSTRAINT `chk_product_publication` CHECK ((((`status` = _utf8mb4'DRAFT') and (`first_on_sale_at` is null)) or ((`status` in (_utf8mb4'ON_SALE',_utf8mb4'OFF_SALE')) and (`first_on_sale_at` is not null)))),
  CONSTRAINT `chk_product_qualification` CHECK ((((`qualification_type` in (_utf8mb4'PUBLIC',_utf8mb4'PRODUCT_WHITELIST')) and (`qualification_scope` is null) and (`qualification_session_id` is null)) or ((`qualification_type` = _utf8mb4'TICKET_REQUIRED') and (`qualification_scope` is not null) and (((`qualification_scope` = _utf8mb4'ACTIVITY') and (`qualification_session_id` is null)) or ((`qualification_scope` = _utf8mb4'SESSION') and (`qualification_session_id` is not null) and (`qualification_session_id` > 0)))))),
  CONSTRAINT `chk_product_status` CHECK ((`status` in (_utf8mb4'DRAFT',_utf8mb4'ON_SALE',_utf8mb4'OFF_SALE')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `product_after_sale_item` (
  `after_sale_item_id` bigint NOT NULL AUTO_INCREMENT,
  `after_sale_request_id` bigint NOT NULL,
  `order_item_id` bigint NOT NULL,
  `sku_id` bigint NOT NULL,
  `reason_code` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `requested_quantity` int NOT NULL,
  `simulate_inspection_passed` tinyint(1) NOT NULL DEFAULT '1',
  `simulate_resalable` tinyint(1) NOT NULL DEFAULT '1',
  `status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'PENDING',
  `returned_quantity` int NOT NULL DEFAULT '0',
  `refunded_quantity` int NOT NULL DEFAULT '0',
  `returned_at` datetime(3) DEFAULT NULL,
  `inspection_result` varchar(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `is_resalable` tinyint(1) DEFAULT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`after_sale_item_id`),
  UNIQUE KEY `uq_product_after_sale_item_order_item` (`after_sale_request_id`,`order_item_id`),
  KEY `idx_product_after_sale_item_order_item` (`order_item_id`,`after_sale_item_id`),
  KEY `idx_product_after_sale_item_sku` (`sku_id`,`after_sale_item_id`),
  CONSTRAINT `chk_product_after_sale_item_ids` CHECK (((`after_sale_request_id` > 0) and (`order_item_id` > 0) and (`sku_id` > 0))),
  CONSTRAINT `chk_product_after_sale_item_inspection` CHECK ((((`inspection_result` = _utf8mb4'NOT_REQUIRED') and (`returned_quantity` = 0) and (`is_resalable` is null)) or ((`inspection_result` = _utf8mb4'PENDING') and (`is_resalable` is null)) or ((`inspection_result` = _utf8mb4'PASSED') and (`returned_quantity` > 0) and (`is_resalable` is not null)) or ((`inspection_result` = _utf8mb4'FAILED') and (`returned_quantity` > 0) and (`is_resalable` = false)))),
  CONSTRAINT `chk_product_after_sale_item_inspection_input` CHECK (((`simulate_inspection_passed` = true) or (`simulate_resalable` = false))),
  CONSTRAINT `chk_product_after_sale_item_quantities` CHECK (((`requested_quantity` > 0) and (`returned_quantity` >= 0) and (`returned_quantity` <= `requested_quantity`) and (`refunded_quantity` >= 0) and (`refunded_quantity` <= `requested_quantity`))),
  CONSTRAINT `chk_product_after_sale_item_reason` CHECK ((`reason_code` in (_utf8mb4'NO_REASON',_utf8mb4'QUALITY_PROBLEM',_utf8mb4'TICKET_QUALIFICATION_LOST',_utf8mb4'ACTIVITY_CANCELLED'))),
  CONSTRAINT `chk_product_after_sale_item_return_time` CHECK ((((`returned_quantity` = 0) and (`returned_at` is null)) or ((`returned_quantity` > 0) and (`returned_at` is not null)))),
  CONSTRAINT `chk_product_after_sale_item_status` CHECK ((`status` in (_utf8mb4'PENDING',_utf8mb4'PROCESSING',_utf8mb4'SUCCEEDED',_utf8mb4'FAILED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `product_after_sale_request` (
  `after_sale_request_id` bigint NOT NULL AUTO_INCREMENT,
  `order_id` bigint NOT NULL,
  `buyer_id` bigint NOT NULL,
  `request_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `reason` varchar(500) NOT NULL,
  `requested_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'PENDING',
  `failure_reason` varchar(500) DEFAULT NULL,
  PRIMARY KEY (`after_sale_request_id`),
  UNIQUE KEY `uq_product_after_sale_request` (`buyer_id`,`request_id`),
  KEY `idx_product_after_sale_request_order` (`order_id`,`requested_at`,`after_sale_request_id`),
  KEY `idx_product_after_sale_request_processing` (`status`,`requested_at`,`after_sale_request_id`),
  CONSTRAINT `chk_product_after_sale_request_failure` CHECK ((((`status` = _utf8mb4'FAILED') and (`failure_reason` is not null) and (char_length(trim(`failure_reason`)) > 0)) or ((`status` <> _utf8mb4'FAILED') and (`failure_reason` is null)))),
  CONSTRAINT `chk_product_after_sale_request_ids` CHECK (((`order_id` > 0) and (`buyer_id` > 0))),
  CONSTRAINT `chk_product_after_sale_request_status` CHECK ((`status` in (_utf8mb4'PENDING',_utf8mb4'PROCESSING',_utf8mb4'SUCCEEDED',_utf8mb4'FAILED'))),
  CONSTRAINT `chk_product_after_sale_request_text` CHECK (((char_length(trim(`request_id`)) > 0) and (char_length(trim(`reason`)) > 0)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `product_order` (
  `order_id` bigint NOT NULL AUTO_INCREMENT,
  `buyer_id` bigint NOT NULL,
  `publisher_id` bigint NOT NULL,
  `request_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `total_amount` decimal(12,2) NOT NULL,
  `status` varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'PENDING_PAYMENT',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `expires_at` datetime(3) GENERATED ALWAYS AS ((`created_at` + interval 15 minute)) STORED,
  `paid_at` datetime(3) DEFAULT NULL,
  `closed_reason` varchar(100) DEFAULT NULL,
  PRIMARY KEY (`order_id`),
  UNIQUE KEY `uq_product_order_request` (`buyer_id`,`request_id`),
  KEY `idx_product_order_buyer_created` (`buyer_id`,`created_at`,`order_id`),
  KEY `idx_product_order_publisher_status` (`publisher_id`,`status`,`order_id`),
  KEY `idx_product_order_expiry` (`status`,`expires_at`,`order_id`),
  CONSTRAINT `chk_product_order_amount` CHECK ((`total_amount` > 0)),
  CONSTRAINT `chk_product_order_closed_reason` CHECK ((((`status` = _utf8mb4'CLOSED') and (`closed_reason` is not null) and (char_length(trim(`closed_reason`)) > 0)) or ((`status` <> _utf8mb4'CLOSED') and (`closed_reason` is null)))),
  CONSTRAINT `chk_product_order_ids` CHECK (((`buyer_id` > 0) and (`publisher_id` > 0))),
  CONSTRAINT `chk_product_order_paid_at` CHECK ((((`status` in (_utf8mb4'PENDING_PAYMENT',_utf8mb4'CLOSED')) and (`paid_at` is null)) or ((`status` in (_utf8mb4'PAID',_utf8mb4'PARTIALLY_REFUNDED',_utf8mb4'REFUNDED')) and (`paid_at` is not null)))),
  CONSTRAINT `chk_product_order_request` CHECK ((char_length(trim(`request_id`)) > 0)),
  CONSTRAINT `chk_product_order_status` CHECK ((`status` in (_utf8mb4'PENDING_PAYMENT',_utf8mb4'PAID',_utf8mb4'PARTIALLY_REFUNDED',_utf8mb4'REFUNDED',_utf8mb4'CLOSED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `product_order_item` (
  `order_item_id` bigint NOT NULL AUTO_INCREMENT,
  `order_id` bigint NOT NULL,
  `product_id` bigint NOT NULL,
  `sku_id` bigint NOT NULL,
  `quantity` int NOT NULL,
  `unit_price` decimal(10,2) NOT NULL,
  `product_name_snapshot` varchar(100) NOT NULL,
  `sku_name_snapshot` varchar(100) NOT NULL,
  `activity_id_snapshot` bigint NOT NULL,
  `qualification_type_snapshot` varchar(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `qualification_scope_snapshot` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin DEFAULT NULL,
  `qualification_session_id_snapshot` bigint DEFAULT NULL,
  `refund_policy_template_snapshot` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `refund_policy_version_snapshot` smallint NOT NULL,
  `special_policy_acknowledged` tinyint(1) NOT NULL DEFAULT '0',
  `policy_acknowledged_at` datetime(3) DEFAULT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `qualification_guard_version_snapshot` bigint DEFAULT NULL,
  `fulfillment_status` varchar(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'UNSHIPPED',
  `shipped_quantity` int NOT NULL DEFAULT '0',
  `received_quantity` int NOT NULL DEFAULT '0',
  `shipped_at` datetime(3) DEFAULT NULL,
  `received_at` datetime(3) DEFAULT NULL,
  `refund_pending_quantity` int NOT NULL DEFAULT '0',
  `refunded_quantity` int NOT NULL DEFAULT '0',
  `returned_quantity` int NOT NULL DEFAULT '0',
  `restocked_quantity` int NOT NULL DEFAULT '0',
  PRIMARY KEY (`order_item_id`),
  UNIQUE KEY `uq_product_order_item_sku` (`order_id`,`sku_id`),
  KEY `idx_product_order_item_product` (`product_id`,`order_id`),
  KEY `idx_product_order_item_sku` (`sku_id`,`order_id`),
  KEY `idx_product_order_item_qualification` (`activity_id_snapshot`,`qualification_type_snapshot`,`qualification_session_id_snapshot`,`order_id`),
  KEY `idx_product_order_item_order_item` (`order_id`,`order_item_id`),
  CONSTRAINT `chk_product_order_item_after_sale_quantities` CHECK (((`refund_pending_quantity` >= 0) and (`refunded_quantity` >= 0) and ((`refund_pending_quantity` + `refunded_quantity`) <= `quantity`) and (`returned_quantity` >= 0) and (`returned_quantity` <= `shipped_quantity`) and (`returned_quantity` <= `refunded_quantity`) and (`restocked_quantity` >= 0) and (`restocked_quantity` <= `refunded_quantity`))),
  CONSTRAINT `chk_product_order_item_fulfillment_quantity` CHECK (((`shipped_quantity` >= 0) and (`shipped_quantity` <= `quantity`) and (`received_quantity` >= 0) and (`received_quantity` <= `shipped_quantity`))),
  CONSTRAINT `chk_product_order_item_fulfillment_state` CHECK ((((`fulfillment_status` = _utf8mb4'UNSHIPPED') and (`shipped_quantity` = 0) and (`received_quantity` = 0) and (`shipped_at` is null) and (`received_at` is null)) or ((`fulfillment_status` = _utf8mb4'SHIPPED') and (`shipped_quantity` > 0) and (`received_quantity` = 0) and (`shipped_at` is not null) and (`received_at` is null)) or ((`fulfillment_status` = _utf8mb4'RECEIVED') and (`shipped_quantity` > 0) and (`received_quantity` = `shipped_quantity`) and (`shipped_at` is not null) and (`received_at` is not null)))),
  CONSTRAINT `chk_product_order_item_fulfillment_time` CHECK (((`received_at` is null) or ((`shipped_at` is not null) and (`received_at` >= `shipped_at`)))),
  CONSTRAINT `chk_product_order_item_guard_version` CHECK ((((`qualification_type_snapshot` = _utf8mb4'TICKET_REQUIRED') and ((`qualification_guard_version_snapshot` is null) or (`qualification_guard_version_snapshot` >= 0))) or ((`qualification_type_snapshot` <> _utf8mb4'TICKET_REQUIRED') and (`qualification_guard_version_snapshot` is null)))),
  CONSTRAINT `chk_product_order_item_ids` CHECK (((`order_id` > 0) and (`product_id` > 0) and (`sku_id` > 0) and (`activity_id_snapshot` > 0))),
  CONSTRAINT `chk_product_order_item_names` CHECK (((char_length(trim(`product_name_snapshot`)) > 0) and (char_length(trim(`sku_name_snapshot`)) > 0))),
  CONSTRAINT `chk_product_order_item_policy_ack` CHECK ((((`refund_policy_template_snapshot` = _utf8mb4'SPECIAL_NO_REASON_RETURN') and (`special_policy_acknowledged` = true) and (`policy_acknowledged_at` is not null)) or ((`refund_policy_template_snapshot` = _utf8mb4'STANDARD_7D') and (`special_policy_acknowledged` = false) and (`policy_acknowledged_at` is null)))),
  CONSTRAINT `chk_product_order_item_price` CHECK ((`unit_price` > 0)),
  CONSTRAINT `chk_product_order_item_qualification` CHECK ((((`qualification_type_snapshot` in (_utf8mb4'PUBLIC',_utf8mb4'PRODUCT_WHITELIST')) and (`qualification_scope_snapshot` is null) and (`qualification_session_id_snapshot` is null)) or ((`qualification_type_snapshot` = _utf8mb4'TICKET_REQUIRED') and (((`qualification_scope_snapshot` = _utf8mb4'ACTIVITY') and (`qualification_session_id_snapshot` is null)) or ((`qualification_scope_snapshot` = _utf8mb4'SESSION') and (`qualification_session_id_snapshot` is not null) and (`qualification_session_id_snapshot` > 0)))))),
  CONSTRAINT `chk_product_order_item_quantity` CHECK ((`quantity` > 0)),
  CONSTRAINT `chk_product_order_item_refund_policy` CHECK (((`refund_policy_template_snapshot` in (_utf8mb4'STANDARD_7D',_utf8mb4'SPECIAL_NO_REASON_RETURN')) and (`refund_policy_version_snapshot` > 0)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `product_whitelist_member` (
  `member_id` bigint NOT NULL AUTO_INCREMENT,
  `product_id` bigint NOT NULL,
  `user_id` bigint NOT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`member_id`),
  UNIQUE KEY `uq_product_member` (`product_id`,`user_id`),
  KEY `idx_product_member_user` (`user_id`,`product_id`),
  CONSTRAINT `chk_product_member_ids` CHECK (((`product_id` > 0) and (`user_id` > 0)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `reschedule_affected_ticket` (
  `event_id` bigint NOT NULL,
  `ticket_id` bigint NOT NULL,
  `buyer_id` bigint NOT NULL,
  `order_id` bigint NOT NULL,
  `session_id` bigint NOT NULL,
  `seat_id` bigint NOT NULL,
  `original_paid_amount` decimal(10,2) NOT NULL,
  PRIMARY KEY (`event_id`,`ticket_id`),
  KEY `idx_reschedule_buyer` (`buyer_id`,`event_id`,`ticket_id`),
  CONSTRAINT `chk_reschedule_snapshot` CHECK (((`event_id` > 0) and (`ticket_id` > 0) and (`buyer_id` > 0) and (`order_id` > 0) and (`session_id` > 0) and (`seat_id` > 0) and (`original_paid_amount` > 0)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `reschedule_refund_link` (
  `submission_id` bigint NOT NULL,
  `refund_id` bigint NOT NULL,
  PRIMARY KEY (`submission_id`,`refund_id`),
  UNIQUE KEY `uq_reschedule_link_refund` (`refund_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `reschedule_refund_submission` (
  `submission_id` bigint NOT NULL AUTO_INCREMENT,
  `event_id` bigint NOT NULL,
  `buyer_id` bigint NOT NULL,
  `request_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `ticket_ids` varchar(2200) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `accepted_at` datetime(3) NOT NULL,
  PRIMARY KEY (`submission_id`),
  UNIQUE KEY `uq_reschedule_refund_submission` (`buyer_id`,`request_id`),
  CONSTRAINT `chk_reschedule_submission` CHECK (((`event_id` > 0) and (`buyer_id` > 0) and (char_length(trim(`request_id`)) > 0) and (char_length(`ticket_ids`) > 0)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `reschedule_response` (
  `event_id` bigint NOT NULL,
  `ticket_id` bigint NOT NULL,
  `status` varchar(20) NOT NULL DEFAULT 'PENDING',
  `responded_at` datetime(3) DEFAULT NULL,
  `auto_retained_at` datetime(3) DEFAULT NULL,
  `source` varchar(24) DEFAULT NULL,
  PRIMARY KEY (`event_id`,`ticket_id`),
  KEY `idx_reschedule_pending` (`status`,`event_id`,`ticket_id`),
  CONSTRAINT `chk_reschedule_response` CHECK ((((`status` = _utf8mb4'PENDING') and (`responded_at` is null) and (`auto_retained_at` is null) and (`source` is null)) or ((`status` = _utf8mb4'USER_CONFIRMED') and (`responded_at` is not null) and (`auto_retained_at` is null) and (`source` is not null) and (`source` = _utf8mb4'USER')) or ((`status` = _utf8mb4'AUTO_RETAINED') and (`responded_at` is null) and (`auto_retained_at` is not null) and (`source` is not null) and (`source` in (_utf8mb4'MQ',_utf8mb4'DEADLINE_SCAN')))))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `session_reschedule_event` (
  `event_id` bigint NOT NULL AUTO_INCREMENT,
  `session_id` bigint NOT NULL,
  `request_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `old_start_time` datetime(3) NOT NULL,
  `old_end_time` datetime(3) NOT NULL,
  `new_start_time` datetime(3) NOT NULL,
  `new_end_time` datetime(3) NOT NULL,
  `reason` varchar(500) NOT NULL,
  `operator_id` bigint NOT NULL,
  `announcement_time` datetime(3) NOT NULL,
  `special_refund_deadline` datetime(3) NOT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`event_id`),
  UNIQUE KEY `uq_reschedule_request` (`session_id`,`request_id`),
  KEY `idx_reschedule_session` (`session_id`,`event_id`),
  KEY `idx_reschedule_due` (`special_refund_deadline`,`event_id`),
  CONSTRAINT `chk_reschedule_ids` CHECK (((`session_id` > 0) and (`operator_id` > 0))),
  CONSTRAINT `chk_reschedule_text` CHECK (((char_length(trim(`request_id`)) > 0) and (char_length(trim(`reason`)) > 0))),
  CONSTRAINT `chk_reschedule_times` CHECK (((`old_start_time` < `old_end_time`) and (`new_start_time` < `new_end_time`) and (`announcement_time` < `new_start_time`) and (`announcement_time` < `special_refund_deadline`) and (`special_refund_deadline` <= `new_start_time`) and (`special_refund_deadline` <= (`announcement_time` + interval 7 day))))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `session_seat` (
  `seat_id` bigint NOT NULL AUTO_INCREMENT,
  `zone_id` bigint NOT NULL COMMENT 'Logical reference to session_zone.zone_id',
  `row_no` int NOT NULL,
  `seat_no` int NOT NULL,
  `allocation_priority` int NOT NULL,
  `allocation_status` varchar(20) NOT NULL DEFAULT 'AVAILABLE',
  PRIMARY KEY (`seat_id`),
  UNIQUE KEY `uq_seat_zone_position` (`zone_id`,`row_no`,`seat_no`),
  KEY `idx_seat_zone_availability_priority` (`zone_id`,`allocation_status`,`allocation_priority`,`seat_id`),
  CONSTRAINT `chk_seat_allocation_status` CHECK ((`allocation_status` in (_utf8mb4'AVAILABLE',_utf8mb4'SOLD',_utf8mb4'GIFT_ALLOCATED',_utf8mb4'GIFT_CANCELLED'))),
  CONSTRAINT `chk_seat_number` CHECK ((`seat_no` > 0)),
  CONSTRAINT `chk_seat_priority` CHECK ((`allocation_priority` > 0)),
  CONSTRAINT `chk_seat_row` CHECK ((`row_no` > 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `session_zone` (
  `zone_id` bigint NOT NULL AUTO_INCREMENT,
  `session_id` bigint NOT NULL COMMENT 'Logical reference to activity_session.activity_session_id',
  `name` varchar(100) NOT NULL,
  `zone_type` varchar(10) NOT NULL,
  `price` decimal(10,2) NOT NULL,
  `total_amount` int NOT NULL,
  `remains_amount` int NOT NULL,
  PRIMARY KEY (`zone_id`),
  UNIQUE KEY `uq_zone_session_name` (`session_id`,`name`),
  KEY `idx_zone_session_type` (`session_id`,`zone_type`),
  CONSTRAINT `chk_zone_inventory` CHECK (((`total_amount` > 0) and (`remains_amount` >= 0) and (`remains_amount` <= `total_amount`))),
  CONSTRAINT `chk_zone_price` CHECK ((((`zone_type` = _utf8mb4'PAID') and (`price` > 0)) or ((`zone_type` = _utf8mb4'GIFT') and (`price` = 0)))),
  CONSTRAINT `chk_zone_type` CHECK ((`zone_type` in (_utf8mb4'PAID',_utf8mb4'GIFT')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `sku` (
  `sku_id` bigint NOT NULL AUTO_INCREMENT,
  `product_id` bigint NOT NULL,
  `sku_name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `price` decimal(10,2) NOT NULL,
  `total_stock` int NOT NULL,
  `remains_stock` int NOT NULL,
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'ACTIVE',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`sku_id`),
  UNIQUE KEY `uq_sku_product_name` (`product_id`,`sku_name`),
  KEY `idx_sku_product_status` (`product_id`,`status`,`sku_id`),
  CONSTRAINT `chk_sku_name` CHECK (((char_length(trim(`sku_name`)) > 0) and (`sku_name` = trim(`sku_name`)))),
  CONSTRAINT `chk_sku_price` CHECK ((`price` > 0)),
  CONSTRAINT `chk_sku_product` CHECK ((`product_id` > 0)),
  CONSTRAINT `chk_sku_status` CHECK ((`status` in (_utf8mb4'ACTIVE',_utf8mb4'INACTIVE'))),
  CONSTRAINT `chk_sku_stock` CHECK (((`total_stock` >= 0) and (`remains_stock` >= 0) and (`remains_stock` <= `total_stock`)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `ticket` (
  `ticket_id` bigint NOT NULL AUTO_INCREMENT,
  `order_item_id` bigint NOT NULL COMMENT 'Logical reference to ticket_order_item.order_item_id',
  `seat_id` bigint NOT NULL COMMENT 'Logical reference to session_seat.seat_id',
  `paid_price` decimal(10,2) NOT NULL COMMENT 'Snapshot of the actual transaction price for this ticket',
  `status` varchar(20) NOT NULL DEFAULT 'VALID',
  `issued_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `refunded_at` datetime(3) DEFAULT NULL,
  `active_seat_id` bigint GENERATED ALWAYS AS ((case when (`status` = _utf8mb4'VALID') then `seat_id` else NULL end)) VIRTUAL,
  PRIMARY KEY (`ticket_id`),
  UNIQUE KEY `uq_ticket_active_seat` (`active_seat_id`),
  KEY `idx_ticket_item` (`order_item_id`),
  KEY `idx_ticket_seat_history` (`seat_id`,`issued_at`),
  CONSTRAINT `chk_ticket_paid_price` CHECK ((`paid_price` > 0)),
  CONSTRAINT `chk_ticket_refunded_time` CHECK (((`refunded_at` is null) or (`refunded_at` >= `issued_at`))),
  CONSTRAINT `chk_ticket_status` CHECK ((`status` in (_utf8mb4'VALID',_utf8mb4'REFUNDED'))),
  CONSTRAINT `chk_ticket_status_time` CHECK ((((`status` = _utf8mb4'VALID') and (`refunded_at` is null)) or ((`status` = _utf8mb4'REFUNDED') and (`refunded_at` is not null))))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `ticket_order` (
  `order_id` bigint NOT NULL AUTO_INCREMENT,
  `buyer_id` bigint NOT NULL COMMENT 'Logical reference to app_user.id',
  `session_id` bigint NOT NULL COMMENT 'Logical reference to activity_session.activity_session_id',
  `request_id` varchar(64) NOT NULL COMMENT 'Idempotency key for order creation requests by the same user',
  `total_amount` decimal(12,2) NOT NULL,
  `status` varchar(30) NOT NULL DEFAULT 'PENDING_PAYMENT',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `expires_at` datetime(3) GENERATED ALWAYS AS ((`created_at` + interval 15 minute)) STORED,
  `paid_at` datetime(3) DEFAULT NULL,
  `closed_reason` varchar(100) DEFAULT NULL,
  PRIMARY KEY (`order_id`),
  UNIQUE KEY `uq_ticket_order_request` (`buyer_id`,`request_id`),
  KEY `idx_ticket_order_buyer_created` (`buyer_id`,`created_at`),
  KEY `idx_ticket_order_session_status` (`session_id`,`status`),
  KEY `idx_ticket_order_expiry` (`status`,`expires_at`),
  CONSTRAINT `chk_ticket_order_amount` CHECK ((`total_amount` > 0)),
  CONSTRAINT `chk_ticket_order_paid_time` CHECK (((`paid_at` is null) or ((`paid_at` >= `created_at`) and (`paid_at` < `expires_at`)))),
  CONSTRAINT `chk_ticket_order_request_id` CHECK ((char_length(trim(`request_id`)) > 0)),
  CONSTRAINT `chk_ticket_order_status` CHECK ((`status` in (_utf8mb4'PENDING_PAYMENT',_utf8mb4'PAID',_utf8mb4'PARTIALLY_REFUNDED',_utf8mb4'REFUNDED',_utf8mb4'CLOSED'))),
  CONSTRAINT `chk_ticket_order_status_fields` CHECK ((((`status` = _utf8mb4'PENDING_PAYMENT') and (`paid_at` is null) and (`closed_reason` is null)) or ((`status` = _utf8mb4'CLOSED') and (`paid_at` is null) and (`closed_reason` is not null)) or ((`status` in (_utf8mb4'PAID',_utf8mb4'PARTIALLY_REFUNDED',_utf8mb4'REFUNDED')) and (`paid_at` is not null) and (`closed_reason` is null))))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `ticket_order_item` (
  `order_item_id` bigint NOT NULL AUTO_INCREMENT,
  `order_id` bigint NOT NULL COMMENT 'Logical reference to ticket_order.order_id',
  `zone_id` bigint NOT NULL COMMENT 'Logical reference to session_zone.zone_id',
  `allocation_mode` varchar(20) NOT NULL,
  `requested_seat_id` bigint DEFAULT NULL COMMENT 'Specified for user-selected seating; NULL for automatic seat assignment',
  `quantity` int NOT NULL,
  `unit_price` decimal(10,2) NOT NULL COMMENT 'Snapshot of the per-ticket price for the zone, confirmed at order creation',
  `accept_non_adjacent` tinyint NOT NULL DEFAULT '0',
  PRIMARY KEY (`order_item_id`),
  UNIQUE KEY `uq_ticket_item_requested_seat` (`order_id`,`requested_seat_id`),
  KEY `idx_ticket_item_zone` (`zone_id`),
  CONSTRAINT `chk_ticket_item_mode` CHECK ((`allocation_mode` in (_utf8mb4'SELECT_SEAT',_utf8mb4'AUTO_ASSIGN'))),
  CONSTRAINT `chk_ticket_item_mode_fields` CHECK ((((`allocation_mode` = _utf8mb4'SELECT_SEAT') and (`requested_seat_id` is not null) and (`quantity` = 1) and (`accept_non_adjacent` = 0)) or ((`allocation_mode` = _utf8mb4'AUTO_ASSIGN') and (`requested_seat_id` is null)))),
  CONSTRAINT `chk_ticket_item_non_adjacent` CHECK ((`accept_non_adjacent` in (0,1))),
  CONSTRAINT `chk_ticket_item_quantity` CHECK ((`quantity` > 0)),
  CONSTRAINT `chk_ticket_item_unit_price` CHECK ((`unit_price` > 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `ticket_qualification_after_sale_task` (
  `task_id` bigint NOT NULL AUTO_INCREMENT,
  `source_event_id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `product_order_item_id` bigint NOT NULL,
  `qualification_scope` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'PENDING',
  `attempt_count` int NOT NULL DEFAULT '0',
  `next_attempt_at` datetime(3) DEFAULT CURRENT_TIMESTAMP(3),
  `failure_reason` varchar(1000) DEFAULT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`task_id`),
  UNIQUE KEY `uq_ticket_qualification_task_order_item` (`product_order_item_id`),
  KEY `idx_ticket_qualification_task_source` (`source_event_id`,`task_id`),
  KEY `idx_ticket_qualification_task_due` (`status`,`next_attempt_at`,`task_id`),
  CONSTRAINT `chk_ticket_qualification_task_attempts` CHECK ((`attempt_count` between 0 and 5)),
  CONSTRAINT `chk_ticket_qualification_task_failure` CHECK ((((`status` = _utf8mb4'FAILED') and (`failure_reason` is not null) and (char_length(trim(`failure_reason`)) > 0)) or ((`status` <> _utf8mb4'FAILED') and (`failure_reason` is null)))),
  CONSTRAINT `chk_ticket_qualification_task_item` CHECK ((`product_order_item_id` > 0)),
  CONSTRAINT `chk_ticket_qualification_task_schedule` CHECK ((((`status` = _utf8mb4'WAITING_RETURN') and (`next_attempt_at` is null)) or ((`status` = _utf8mb4'SKIPPED') and (`next_attempt_at` is null)) or ((`status` = _utf8mb4'SUCCEEDED') and (`next_attempt_at` is null)) or ((`status` = _utf8mb4'FAILED') and (`attempt_count` >= 5) and (`next_attempt_at` is null)) or ((`status` in (_utf8mb4'PENDING',_utf8mb4'PROCESSING')) and (`next_attempt_at` is not null)) or ((`status` = _utf8mb4'FAILED') and (`attempt_count` < 5) and (`next_attempt_at` is not null)))),
  CONSTRAINT `chk_ticket_qualification_task_scope` CHECK ((`qualification_scope` in (_utf8mb4'ACTIVITY',_utf8mb4'SESSION'))),
  CONSTRAINT `chk_ticket_qualification_task_status` CHECK ((`status` in (_utf8mb4'PENDING',_utf8mb4'PROCESSING',_utf8mb4'WAITING_RETURN',_utf8mb4'SKIPPED',_utf8mb4'SUCCEEDED',_utf8mb4'FAILED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `ticket_refund_item` (
  `refund_item_id` bigint NOT NULL AUTO_INCREMENT,
  `refund_id` bigint NOT NULL,
  `ticket_id` bigint NOT NULL,
  `original_price` decimal(10,2) NOT NULL,
  `fee_rate` decimal(5,4) NOT NULL,
  `refund_amount` decimal(10,2) NOT NULL,
  `status` varchar(20) NOT NULL DEFAULT 'PENDING',
  `failure_reason` varchar(500) DEFAULT NULL,
  `claimed_ticket_id` bigint GENERATED ALWAYS AS ((case when (`status` in (_utf8mb4'PENDING',_utf8mb4'PROCESSING',_utf8mb4'SUCCEEDED')) then `ticket_id` else NULL end)) VIRTUAL,
  `processed_at` datetime(3) DEFAULT NULL,
  `result` varchar(100) DEFAULT NULL,
  PRIMARY KEY (`refund_item_id`),
  UNIQUE KEY `uq_refund_item_ticket` (`refund_id`,`ticket_id`),
  UNIQUE KEY `uq_refund_ticket_claim` (`claimed_ticket_id`),
  KEY `idx_refund_item_ticket_history` (`ticket_id`,`refund_item_id`),
  CONSTRAINT `chk_refund_item_amount` CHECK (((`refund_amount` >= 0) and (`refund_amount` <= `original_price`) and (`refund_amount` = round((`original_price` * (1 - `fee_rate`)),2)))),
  CONSTRAINT `chk_refund_item_failure` CHECK ((((`status` = _utf8mb4'FAILED') and (`failure_reason` is not null) and (char_length(trim(`failure_reason`)) > 0)) or ((`status` <> _utf8mb4'FAILED') and (`failure_reason` is null)))),
  CONSTRAINT `chk_refund_item_ids` CHECK (((`refund_id` > 0) and (`ticket_id` > 0))),
  CONSTRAINT `chk_refund_item_price` CHECK ((`original_price` > 0)),
  CONSTRAINT `chk_refund_item_rate` CHECK (((`fee_rate` >= 0) and (`fee_rate` <= 1))),
  CONSTRAINT `chk_refund_item_status` CHECK ((`status` in (_utf8mb4'PENDING',_utf8mb4'PROCESSING',_utf8mb4'SUCCEEDED',_utf8mb4'FAILED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `ticket_refund_request` (
  `refund_id` bigint NOT NULL AUTO_INCREMENT,
  `buyer_id` bigint NOT NULL,
  `order_id` bigint NOT NULL,
  `request_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `reason` varchar(500) NOT NULL,
  `requested_at` datetime(3) NOT NULL,
  `status` varchar(20) NOT NULL DEFAULT 'PENDING',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `policy_version` varchar(32) NOT NULL DEFAULT 'ORDINARY_V1',
  `open_time_snapshot` datetime(3) NOT NULL,
  `start_time_snapshot` datetime(3) NOT NULL,
  `processed_at` datetime(3) DEFAULT NULL,
  `result` varchar(100) DEFAULT NULL,
  `refund_policy` varchar(32) NOT NULL DEFAULT 'NORMAL_REFUND',
  `source_event_id` bigint DEFAULT NULL,
  `accepted_at` datetime(3) DEFAULT NULL,
  PRIMARY KEY (`refund_id`),
  UNIQUE KEY `uq_refund_request` (`buyer_id`,`request_id`),
  KEY `idx_refund_request_order` (`order_id`,`refund_id`),
  KEY `idx_refund_request_processing` (`status`,`refund_id`),
  KEY `idx_refund_source_event` (`source_event_id`,`buyer_id`,`refund_id`),
  CONSTRAINT `chk_refund_policy_source` CHECK ((((`refund_policy` = _utf8mb4'NORMAL_REFUND') and (`source_event_id` is null)) or ((`refund_policy` in (_utf8mb4'SESSION_RESCHEDULE_REFUND',_utf8mb4'CANCELLATION_REFUND')) and (`source_event_id` is not null) and (`source_event_id` > 0) and (`accepted_at` is not null) and (`accepted_at` = `requested_at`)))),
  CONSTRAINT `chk_refund_request_ids` CHECK (((`buyer_id` > 0) and (`order_id` > 0))),
  CONSTRAINT `chk_refund_request_status` CHECK ((`status` in (_utf8mb4'PENDING',_utf8mb4'PROCESSING',_utf8mb4'SUCCEEDED',_utf8mb4'FAILED'))),
  CONSTRAINT `chk_refund_request_text` CHECK (((char_length(trim(`request_id`)) > 0) and (char_length(trim(`reason`)) > 0))),
  CONSTRAINT `chk_refund_request_time` CHECK (((`refund_policy` = _utf8mb4'CANCELLATION_REFUND') or (`requested_at` < `start_time_snapshot`)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `user_activity_qualification_guard` (
  `user_id` bigint NOT NULL,
  `activity_id` bigint NOT NULL,
  `qualification_version` bigint NOT NULL DEFAULT '0',
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`user_id`,`activity_id`),
  KEY `idx_qualification_guard_activity_user` (`activity_id`,`user_id`),
  CONSTRAINT `chk_user_activity_qualification_guard_ids` CHECK (((`user_id` > 0) and (`activity_id` > 0))),
  CONSTRAINT `chk_user_activity_qualification_guard_version` CHECK ((`qualification_version` >= 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `user_product_purchase` (
  `user_id` bigint NOT NULL,
  `product_id` bigint NOT NULL,
  `valid_count` int NOT NULL DEFAULT '0',
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`user_id`,`product_id`),
  KEY `idx_user_product_purchase_product` (`product_id`,`user_id`),
  CONSTRAINT `chk_user_product_purchase_count` CHECK ((`valid_count` >= 0)),
  CONSTRAINT `chk_user_product_purchase_ids` CHECK (((`user_id` > 0) and (`product_id` > 0)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `user_session_purchase` (
  `user_id` bigint NOT NULL COMMENT 'Logical reference to app_user.id',
  `session_id` bigint NOT NULL COMMENT 'Logical reference to activity_session.activity_session_id',
  `valid_count` int NOT NULL DEFAULT '0' COMMENT 'Current number of valid paid tickets that have not been refunded',
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`user_id`,`session_id`),
  CONSTRAINT `chk_user_session_purchase_count` CHECK ((`valid_count` >= 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `whitelist_config` (
  `config_id` bigint NOT NULL AUTO_INCREMENT,
  `scope_type` varchar(20) NOT NULL,
  `scope_id` bigint NOT NULL COMMENT 'Logical reference to activity or activity_session, selected by scope_type',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`config_id`),
  UNIQUE KEY `uq_whitelist_config_scope` (`scope_type`,`scope_id`),
  CONSTRAINT `chk_whitelist_config_scope_id` CHECK ((`scope_id` > 0)),
  CONSTRAINT `chk_whitelist_config_scope_type` CHECK ((`scope_type` in (_utf8mb4'ACTIVITY',_utf8mb4'SESSION')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `whitelist_member` (
  `member_id` bigint NOT NULL AUTO_INCREMENT,
  `config_id` bigint NOT NULL COMMENT 'Logical reference to whitelist_config.config_id',
  `user_id` bigint NOT NULL COMMENT 'Logical reference to app_user.id',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`member_id`),
  UNIQUE KEY `uq_whitelist_member_config_user` (`config_id`,`user_id`),
  CONSTRAINT `chk_whitelist_member_config_id` CHECK ((`config_id` > 0)),
  CONSTRAINT `chk_whitelist_member_user_id` CHECK ((`user_id` > 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
