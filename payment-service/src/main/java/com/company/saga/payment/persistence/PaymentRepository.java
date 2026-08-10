package com.company.saga.payment.persistence;

import com.company.saga.payment.domain.Payment;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;

import java.util.UUID;

/** Reactive persistence gateway for {@link Payment} aggregates (table {@code payment}). */
public interface PaymentRepository extends ReactiveCrudRepository<Payment, UUID> {
}
