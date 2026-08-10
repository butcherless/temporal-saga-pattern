-- Inventory Service's shared stock catalog (Step 5 plan, decision 3) - a minimal model for
-- this PoC, not a full inventory management system.
CREATE TABLE stock_item (
    sku VARCHAR(50) PRIMARY KEY,
    available_quantity INTEGER NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

-- Seeded with 3 fixed SKUs, one at zero: a free, deterministic fixture for exercising the
-- insufficient-stock permanent-failure branch later, with no magic-number tricks needed.
INSERT INTO stock_item (sku, available_quantity, version) VALUES
    ('SKU-001', 100, 0),
    ('SKU-002', 50, 0),
    ('SKU-003', 0, 0);
