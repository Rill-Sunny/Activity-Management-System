-- Product publishing and eligibility only. Orders, payment quota and qualification guards are later slices.
-- All references are logical; no physical FOREIGN KEY.
CREATE TABLE product (
    product_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    publisher_id BIGINT NOT NULL,
    activity_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(1000) NULL,
    status VARCHAR(16) COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'DRAFT',
    qualification_type VARCHAR(24) COLLATE utf8mb4_0900_bin NOT NULL,
    qualification_scope VARCHAR(16) COLLATE utf8mb4_0900_bin NULL,
    qualification_session_id BIGINT NULL,
    refund_policy_template VARCHAR(32) COLLATE utf8mb4_0900_bin NOT NULL,
    refund_policy_version SMALLINT NOT NULL DEFAULT 1,
    purchase_limit INT NOT NULL,
    first_on_sale_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT chk_product_ids CHECK (publisher_id > 0 AND activity_id > 0),
    CONSTRAINT chk_product_name CHECK (CHAR_LENGTH(TRIM(name)) > 0),
    CONSTRAINT chk_product_status CHECK (status IN ('DRAFT', 'ON_SALE', 'OFF_SALE')),
    CONSTRAINT chk_product_publication CHECK (
        (status = 'DRAFT' AND first_on_sale_at IS NULL)
        OR (status IN ('ON_SALE', 'OFF_SALE') AND first_on_sale_at IS NOT NULL)),
    CONSTRAINT chk_product_qualification CHECK (
        (qualification_type IN ('PUBLIC', 'PRODUCT_WHITELIST') AND qualification_scope IS NULL AND qualification_session_id IS NULL)
        OR (qualification_type = 'TICKET_REQUIRED' AND qualification_scope IS NOT NULL AND (
            (qualification_scope = 'ACTIVITY' AND qualification_session_id IS NULL)
            OR (qualification_scope = 'SESSION' AND qualification_session_id IS NOT NULL AND qualification_session_id > 0)))),
    CONSTRAINT chk_product_policy CHECK (refund_policy_template IN ('STANDARD_7D', 'SPECIAL_NO_REASON_RETURN') AND refund_policy_version = 1),
    CONSTRAINT chk_product_limit CHECK (purchase_limit > 0),
    INDEX idx_product_publisher_status (publisher_id, status, product_id),
    INDEX idx_product_activity_status (activity_id, status, product_id),
    INDEX idx_product_qualification_session (qualification_session_id, product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE sku (
    sku_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    product_id BIGINT NOT NULL,
    sku_name VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
    price DECIMAL(10,2) NOT NULL,
    total_stock INT NOT NULL,
    remains_stock INT NOT NULL,
    status VARCHAR(16) COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT chk_sku_product CHECK (product_id > 0),
    CONSTRAINT chk_sku_name CHECK (CHAR_LENGTH(TRIM(sku_name)) > 0 AND sku_name = TRIM(sku_name)),
    CONSTRAINT chk_sku_price CHECK (price > 0),
    CONSTRAINT chk_sku_stock CHECK (total_stock >= 0 AND remains_stock >= 0 AND remains_stock <= total_stock),
    CONSTRAINT chk_sku_status CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT uq_sku_product_name UNIQUE (product_id, sku_name),
    INDEX idx_sku_product_status (product_id, status, sku_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE product_whitelist_member (
    member_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    product_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT chk_product_member_ids CHECK (product_id > 0 AND user_id > 0),
    CONSTRAINT uq_product_member UNIQUE (product_id, user_id),
    INDEX idx_product_member_user (user_id, product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
