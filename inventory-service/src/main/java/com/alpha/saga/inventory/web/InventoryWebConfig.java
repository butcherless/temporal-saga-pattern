package com.alpha.saga.inventory.web;

import com.alpha.saga.inventory.persistence.InventoryReservationRepository;
import com.alpha.saga.inventory.persistence.StockItemRepository;
import com.alpha.saga.inventory.service.InventoryProgressionService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires {@link InventoryProgressionService} for {@link InventoryController}. */
@Configuration
public class InventoryWebConfig {

    @Bean
    public InventoryProgressionService inventoryProgressionService(
            final StockItemRepository stockItemRepository,
            final InventoryReservationRepository inventoryReservationRepository) {
        return new InventoryProgressionService(stockItemRepository, inventoryReservationRepository);
    }
}
