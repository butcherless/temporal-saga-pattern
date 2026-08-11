-- Backs a quantity-decrease order adjustment (docs/order-adjustment-and-status-query-plan.md,
-- Part A): a standalone refund event for the adjustment's own saga id, deliberately not tied to
-- any row in `payment` (a decrease-only adjustment never had a payment request of its own to
-- refund). related_saga_id is carried for traceability back to the amount/order context; no
-- status column, since unlike `payment` this is a single fire-and-forget event with no later
-- reversal. version exists purely so Spring Data R2DBC's save() treats a freshly created row (its
-- id always non-null, manually assigned to the adjustment's sagaId) as new rather than issuing a
-- silent no-op UPDATE for a nonexistent row.
CREATE TABLE partial_refund (
    id UUID PRIMARY KEY,
    related_saga_id UUID NOT NULL,
    amount NUMERIC(12, 2) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);
