package com.alpha.saga.inventory.domain;

import java.util.Objects;

/**
 * Thrown when code attempts to move a reservation to a {@link ReservationStatus} its current
 * status does not allow. This is an internal invariant violation of Inventory Service itself —
 * not a classified business error — so it does not extend {@code SagaException} from
 * {@code saga-common}.
 */
public final class IllegalReservationTransitionException extends IllegalStateException {

    private final ReservationStatus from;
    private final ReservationStatus to;

    public IllegalReservationTransitionException(final ReservationStatus from,
            final ReservationStatus to) {
        Objects.requireNonNull(from, "from must not be null");
        Objects.requireNonNull(to, "to must not be null");
        super("Illegal reservation transition from %s to %s".formatted(from, to));
        this.from = from;
        this.to = to;
    }

    public ReservationStatus from() {
        return this.from;
    }

    public ReservationStatus to() {
        return this.to;
    }
}
