package com.company.saga.inventory.web;

import com.company.saga.inventory.persistence.InventoryReservationRepository;
import com.company.saga.inventory.persistence.StockItemRepository;
import com.company.saga.inventory.service.InventoryProgressionService;
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
