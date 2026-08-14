package com.alpha.saga.inventory.domain;

import com.alpha.saga.common.error.PermanentSagaException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StockItemTest {

    private static final String SKU = "SKU-001";

    @Test
    void reserveDebitsTheAvailableQuantity() {
        final StockItem stockItem = new StockItem(SKU, 100, 0L);

        final StockItem reserved = stockItem.reserve(10);

        assertThat(reserved.availableQuantity()).isEqualTo(90);
        // The original instance is untouched (records are immutable).
        assertThat(stockItem.availableQuantity()).isEqualTo(100);
    }

    @Test
    void reserveThrowsWhenNotEnoughStockIsAvailable() {
        final StockItem stockItem = new StockItem("SKU-003", 0, 0L);

        assertThatThrownBy(() -> stockItem.reserve(1))
                .isInstanceOf(PermanentSagaException.class)
                .hasMessageContaining("SKU-003");
    }

    @Test
    void releaseCreditsTheAvailableQuantity() {
        final StockItem stockItem = new StockItem(SKU, 90, 0L);

        final StockItem released = stockItem.release(10);

        assertThat(released.availableQuantity()).isEqualTo(100);
    }

    @Test
    void rejectsNonPositiveQuantities() {
        final StockItem stockItem = new StockItem(SKU, 100, 0L);

        assertThatThrownBy(() -> stockItem.reserve(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> stockItem.reserve(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> stockItem.release(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> stockItem.release(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBlankSkuAndNegativeAvailableQuantity() {
        assertThatThrownBy(() -> new StockItem(" ", 100, 0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sku");
        assertThatThrownBy(() -> new StockItem(SKU, -1, 0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("availableQuantity");
    }

    @Test
    void rejectsNullSku() {
        assertThatThrownBy(() -> new StockItem(null, 100, 0L)).isInstanceOf(NullPointerException.class);
    }
}
