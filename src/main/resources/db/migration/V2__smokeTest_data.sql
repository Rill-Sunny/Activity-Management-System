-- Use the database selected by the datasource. Never switch to a developer's schema.

-- Use UTC for DATETIME(3) test fixtures
SET @day1_old_timezone = @@session.time_zone;
SET SESSION time_zone = '+00:00';

START TRANSACTION;

-- 1. Create publisher
-- Test-only BCrypt-style fixture; not for authentication
INSERT INTO app_user
(username, password_hash, role, status)
VALUES (
           CONCAT('smoke_pub_', REPLACE(UUID(), '-', '')),
           '$2a$10$R9h/cIPz0gi.URNNX3kh2OPST9/PgBkqquzi.Ss7KIUgO2t0jWMUW',
           'PUBLISHER',
           'ACTIVE'
       );

SET @publisher_id = LAST_INSERT_ID();

-- 2. Create buyer
INSERT INTO app_user
(username, password_hash, role, status)
VALUES (
           CONCAT('smoke_buyer_', REPLACE(UUID(), '-', '')),
           '$2a$10$R9h/cIPz0gi.URNNX3kh2OPST9/PgBkqquzi.Ss7KIUgO2t0jWMUW',
           'USER',
           'ACTIVE'
       );

SET @buyer_id = LAST_INSERT_ID();

-- 3. Create paid activity
INSERT INTO activity
(publisher_id, name, type, status, open_time, close_time)
VALUES (
           @publisher_id,
           'Day1 Smoke Activity',
           'PAID',
           'DRAFT',
           UTC_TIMESTAMP(3) - INTERVAL 1 DAY,
           UTC_TIMESTAMP(3) + INTERVAL 1 DAY
       );

SET @activity_id = LAST_INSERT_ID();

-- 4. Create session
INSERT INTO activity_session
(
    activity_id,
    start_time,
    end_time,
    capacity,
    status,
    max_tickets_per_user,
    seat_mode
)
VALUES (
           @activity_id,
           UTC_TIMESTAMP(3) + INTERVAL 2 DAY,
           UTC_TIMESTAMP(3) + INTERVAL 3 DAY,
           1,
           'SCHEDULED',
           1,
           'SELECT_SEAT'
       );

SET @session_id = LAST_INSERT_ID();

-- 5. Create paid zone
INSERT INTO session_zone
(
    session_id,
    name,
    zone_type,
    price,
    total_amount,
    remains_amount
)
VALUES (
           @session_id,
           'Smoke Zone A',
           'PAID',
           99.00,
           1,
           1
       );

SET @zone_id = LAST_INSERT_ID();

-- 6. Create seat
INSERT INTO session_seat
(
    zone_id,
    row_no,
    seat_no,
    allocation_priority,
    allocation_status
)
VALUES (
           @zone_id,
           1,
           1,
           1,
           'AVAILABLE'
       );

SET @seat_id = LAST_INSERT_ID();

-- Publish the fixture activity
UPDATE activity
SET status = 'PUBLISHED'
WHERE activity_id = @activity_id;

-- Verify fixture references
SELECT
    @publisher_id AS publisher_id,
    @buyer_id AS buyer_id,
    @activity_id AS activity_id,
    @session_id AS session_id,
    @zone_id AS zone_id,
    @seat_id AS seat_id;

COMMIT;
SET SESSION time_zone = @day1_old_timezone;
