package com.alpha.saga.inventory.persistence;

import com.alpha.saga.inventory.domain.StockItem;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;

/** Reactive persistence gateway for {@link StockItem} rows (table {@code stock_item}). */
public interface StockItemRepository extends ReactiveCrudRepository<StockItem, String> {
}
