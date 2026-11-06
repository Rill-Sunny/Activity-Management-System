-- Keep the V11 migration immutable while allowing inspection and resale eligibility to be recorded independently.
ALTER TABLE product_after_sale_item
    ADD COLUMN simulate_inspection_passed BOOLEAN NOT NULL DEFAULT TRUE AFTER requested_quantity,
    ADD CONSTRAINT chk_product_after_sale_item_inspection_input CHECK (
        simulate_inspection_passed = TRUE OR simulate_resalable = FALSE
    );
