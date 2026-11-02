-- Frozen quotes remain unchanged. Processing results commit with resource restoration.
ALTER TABLE ticket_refund_request
    ADD COLUMN processed_at DATETIME(3) NULL,
    ADD COLUMN result VARCHAR(100) NULL;

ALTER TABLE ticket_refund_item
    ADD COLUMN processed_at DATETIME(3) NULL,
    ADD COLUMN result VARCHAR(100) NULL;
