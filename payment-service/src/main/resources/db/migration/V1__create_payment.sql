-- Payment Service's own payment aggregate (proposal, section 6.3), simplified per the Step 5
-- plan (decision 2): AUTHORIZED/CAPTURED collapse into COMPLETED, no separate confirm status.
-- id is the owning Saga's own id (decision 6) - one payment per saga in this PoC. attempt backs
-- PaymentProgressionService's deterministic fake-gateway rule (decision 4).
CREATE TABLE payment (
    id UUID PRIMARY KEY,
    amount NUMERIC(12, 2) NOT NULL,
    status VARCHAR(50) NOT NULL,
    attempt INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);
