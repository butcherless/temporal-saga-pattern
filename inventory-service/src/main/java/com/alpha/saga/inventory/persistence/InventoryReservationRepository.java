package com.alpha.saga.inventory.persistence;

import com.alpha.saga.inventory.domain.InventoryReservation;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;

import java.util.UUID;

/** Reactive persistence gateway for {@link InventoryReservation} aggregates (table {@code inventory_reservation}). */
public interface InventoryReservationRepository extends ReactiveCrudRepository<InventoryReservation, UUID> {
}
