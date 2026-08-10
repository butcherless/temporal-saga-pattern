-- Two more fixed SKUs (follow-up to Step 6): fixtures for InventoryProgressionService's PoC-only
-- deterministic fault injection (mirrors payment-service's fake gateway), enabling proposal
-- §17.3 scenarios 2 and 6 to be exercised end-to-end without real fault injection. Both seeded
-- with generous stock so the reservation itself always succeeds - only the specific simulated
-- fault (reserve timeout / release failure) fires.
INSERT INTO stock_item (sku, available_quantity, version) VALUES
    ('SKU-INPUTDATA-2', 100, 0),
    ('SKU-INPUTDATA-6', 100, 0);
