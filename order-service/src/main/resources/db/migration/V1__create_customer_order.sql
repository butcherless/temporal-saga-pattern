-- Order Service's own order aggregate (proposal, section 6.1), simplified per the Step 5 plan
-- (decision 1): only the statuses actually driven by a command (PENDING/CONFIRMED/CANCELLED).
-- id is the owning Saga's own id (decision 6) — one order per saga in this PoC.
CREATE TABLE customer_order (
    id UUID PRIMARY KEY,
    business_key VARCHAR(100) NOT NULL,
    status VARCHAR(50) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

-- Defense-in-depth backstop: business_key, not id, is the true idempotency key for this
-- aggregate (decision 5) — the primary check still happens in OrderProgressionService.
CREATE UNIQUE INDEX idx_customer_order_business_key ON customer_order (business_key);
