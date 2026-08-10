-- Per-saga inventory reservation (proposal, section 6.2). id is the owning Saga's own id
-- (Step 5 plan, decision 6) - one reservation per saga in this PoC.
CREATE TABLE inventory_reservation (
    id UUID PRIMARY KEY,
    sku VARCHAR(50) NOT NULL,
    quantity INTEGER NOT NULL,
    status VARCHAR(50) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_inventory_reservation_stock_item
        FOREIGN KEY (sku)
        REFERENCES stock_item (sku)
);

CREATE INDEX idx_inventory_reservation_sku ON inventory_reservation (sku);
