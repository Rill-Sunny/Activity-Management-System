-- MySQL dump 10.13  Distrib 8.4.11, for Win64 (x86_64)
--
-- Host: localhost    Database: limit_activity_system
-- ------------------------------------------------------
-- Server version	8.4.11

/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;

--
-- Table structure for table `activity`
--

DROP TABLE IF EXISTS `activity`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
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
  CONSTRAINT `chk_activity_status` CHECK ((`status` in (_gbk'DRAFT',_gbk'PUBLISHED',_gbk'FINISHED',_gbk'CANCELLED'))),
  CONSTRAINT `chk_activity_type` CHECK ((`type` in (_gbk'FREE',_gbk'PAID')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `activity_session`
--

DROP TABLE IF EXISTS `activity_session`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
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
  CONSTRAINT `chk_session_seat_mode` CHECK (((`seat_mode` is null) or (`seat_mode` in (_gbk'SELECT_SEAT',_gbk'AUTO_ASSIGN')))),
  CONSTRAINT `chk_session_status` CHECK ((`status` in (_gbk'SCHEDULED',_gbk'FINISHED',_gbk'CANCELLED'))),
  CONSTRAINT `chk_session_time` CHECK ((`start_time` < `end_time`))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `app_user`
--

DROP TABLE IF EXISTS `app_user`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `app_user` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `username` varchar(50) NOT NULL,
  `password_hash` varchar(255) NOT NULL COMMENT 'Plaintext transmission is inhibited',
  `role` varchar(20) NOT NULL DEFAULT 'USER',
  `status` varchar(20) NOT NULL DEFAULT 'ACTIVE',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_app_user_username` (`username`),
  CONSTRAINT `chk_app_user_password_hash` CHECK ((char_length(trim(`password_hash`)) > 0)),
  CONSTRAINT `chk_app_user_role` CHECK ((`role` in (_gbk'USER',_gbk'PUBLISHER'))),
  CONSTRAINT `chk_app_user_status` CHECK ((`status` in (_gbk'ACTIVE',_gbk'DISABLED'))),
  CONSTRAINT `chk_app_user_username` CHECK ((char_length(trim(`username`)) > 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `session_seat`
--

DROP TABLE IF EXISTS `session_seat`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
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
  CONSTRAINT `chk_seat_allocation_status` CHECK ((`allocation_status` in (_gbk'AVAILABLE',_gbk'SOLD',_gbk'GIFT_ALLOCATED'))),
  CONSTRAINT `chk_seat_number` CHECK ((`seat_no` > 0)),
  CONSTRAINT `chk_seat_priority` CHECK ((`allocation_priority` > 0)),
  CONSTRAINT `chk_seat_row` CHECK ((`row_no` > 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `session_zone`
--

DROP TABLE IF EXISTS `session_zone`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
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
  CONSTRAINT `chk_zone_price` CHECK ((((`zone_type` = _gbk'PAID') and (`price` > 0)) or ((`zone_type` = _gbk'GIFT') and (`price` = 0)))),
  CONSTRAINT `chk_zone_type` CHECK ((`zone_type` in (_gbk'PAID',_gbk'GIFT')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `ticket`
--

DROP TABLE IF EXISTS `ticket`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
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
  CONSTRAINT `chk_ticket_status` CHECK ((`status` in (_gbk'VALID',_gbk'REFUNDED'))),
  CONSTRAINT `chk_ticket_status_time` CHECK ((((`status` = _gbk'VALID') and (`refunded_at` is null)) or ((`status` = _gbk'REFUNDED') and (`refunded_at` is not null))))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `ticket_order`
--

DROP TABLE IF EXISTS `ticket_order`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
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
  CONSTRAINT `chk_ticket_order_status` CHECK ((`status` in (_gbk'PENDING_PAYMENT',_gbk'PAID',_gbk'PARTIALLY_REFUNDED',_gbk'REFUNDED',_gbk'CLOSED'))),
  CONSTRAINT `chk_ticket_order_status_fields` CHECK ((((`status` = _gbk'PENDING_PAYMENT') and (`paid_at` is null) and (`closed_reason` is null)) or ((`status` = _gbk'CLOSED') and (`paid_at` is null) and (`closed_reason` is not null)) or ((`status` in (_gbk'PAID',_gbk'PARTIALLY_REFUNDED',_gbk'REFUNDED')) and (`paid_at` is not null) and (`closed_reason` is null))))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `ticket_order_item`
--

DROP TABLE IF EXISTS `ticket_order_item`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
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
  CONSTRAINT `chk_ticket_item_mode` CHECK ((`allocation_mode` in (_gbk'SELECT_SEAT',_gbk'AUTO_ASSIGN'))),
  CONSTRAINT `chk_ticket_item_mode_fields` CHECK ((((`allocation_mode` = _gbk'SELECT_SEAT') and (`requested_seat_id` is not null) and (`quantity` = 1) and (`accept_non_adjacent` = 0)) or ((`allocation_mode` = _gbk'AUTO_ASSIGN') and (`requested_seat_id` is null)))),
  CONSTRAINT `chk_ticket_item_non_adjacent` CHECK ((`accept_non_adjacent` in (0,1))),
  CONSTRAINT `chk_ticket_item_quantity` CHECK ((`quantity` > 0)),
  CONSTRAINT `chk_ticket_item_unit_price` CHECK ((`unit_price` > 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `user_session_purchase`
--

DROP TABLE IF EXISTS `user_session_purchase`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `user_session_purchase` (
  `user_id` bigint NOT NULL COMMENT 'Logical reference to app_user.id',
  `session_id` bigint NOT NULL COMMENT 'Logical reference to activity_session.activity_session_id',
  `valid_count` int NOT NULL DEFAULT '0' COMMENT 'Current number of valid paid tickets that have not been refunded',
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`user_id`,`session_id`),
  CONSTRAINT `chk_user_session_purchase_count` CHECK ((`valid_count` >= 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

-- Dump completed on 2026-10-14 14:49:55
