package com.alpha.saga.inventory.domain;

import com.alpha.saga.common.error.AbstractIllegalStatusTransitionException;

/**
 * Thrown when code attempts to move a reservation to a {@link ReservationStatus} its current
 * status does not allow. See {@link AbstractIllegalStatusTransitionException} for why this is an
 * internal invariant violation rather than a classified {@code SagaException}.
 */
public final class IllegalReservationTransitionException extends AbstractIllegalStatusTransitionException {

    public IllegalReservationTransitionException(final ReservationStatus from,
            final ReservationStatus to) {
        super("reservation", from, to);
    }
}
