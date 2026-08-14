package com.alpha.saga.inventory.domain;

import com.alpha.saga.common.error.PermanentSagaException;
import com.alpha.saga.common.util.StringUtils;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

import java.util.Objects;

/**
 * A shared stock catalog row (Step 5 plan, decision 3) — a minimal model for this PoC, not a
 * full inventory management system. Unlike the per-saga aggregates in this codebase, its
 * {@code @Id} is the {@code sku} itself: many sagas reserve against the same row concurrently,
 * so {@link Version optimistic locking} is what keeps concurrent reservations safe.
 */
@Table("stock_item")
public record StockItem(@Id String sku,
        int availableQuantity,
        @Version Long version) {

    public StockItem {
        Objects.requireNonNull(sku, "sku must not be null");
        if (StringUtils.isBlank(sku)) {
            throw new IllegalArgumentException("sku must not be blank");
        }
        if (availableQuantity < 0) {
            throw new IllegalArgumentException("availableQuantity must not be negative");
        }
    }

    /**
     * Debits {@code quantity} from the available stock.
     *
     * @throws PermanentSagaException if fewer than {@code quantity} units are available —
     *         {@code PermanentSagaException} is {@code final} in {@code saga-common}, so this is
     *         thrown directly rather than through a dedicated subclass
     */
    public StockItem reserve(final int quantity) {
        requirePositiveQuantity(quantity);
        if (quantity > this.availableQuantity) {
            throw new PermanentSagaException(
                    "Insufficient stock for %s: requested %d, available %d".formatted(this.sku, quantity, this.availableQuantity));
        }
        return new StockItem(this.sku, this.availableQuantity - quantity, this.version);
    }

    /** Credits {@code quantity} back to the available stock (compensation). */
    public StockItem release(final int quantity) {
        requirePositiveQuantity(quantity);
        return new StockItem(this.sku, this.availableQuantity + quantity, this.version);
    }

    private static void requirePositiveQuantity(final int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
    }
}
