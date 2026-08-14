package com.alpha.saga.payment.persistence;

import com.alpha.saga.payment.domain.PartialRefund;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;

import java.util.UUID;

/** Reactive persistence gateway for {@link PartialRefund} rows (table {@code partial_refund}). */
public interface PartialRefundRepository extends ReactiveCrudRepository<PartialRefund, UUID> {
}
