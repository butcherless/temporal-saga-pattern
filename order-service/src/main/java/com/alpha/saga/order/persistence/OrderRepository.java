package com.alpha.saga.order.persistence;

import com.alpha.saga.order.domain.CustomerOrder;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** Reactive persistence gateway for {@link CustomerOrder} aggregates (table {@code customer_order}). */
public interface OrderRepository extends ReactiveCrudRepository<CustomerOrder, UUID> {

    /** Looks up an order by its idempotency key (proposal §10.5), independent of {@code id}. */
    Mono<CustomerOrder> findByBusinessKey(String businessKey);
}
