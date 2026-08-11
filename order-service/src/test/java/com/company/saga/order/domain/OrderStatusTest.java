package com.company.saga.order.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Set;

import static com.company.saga.order.domain.OrderStatus.CANCELLED;
import static com.company.saga.order.domain.OrderStatus.CONFIRMED;
import static com.company.saga.order.domain.OrderStatus.PENDING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderStatusTest {

    @Test
    void pendingCanBeConfirmedOrCancelled() {
        assertThat(PENDING.canTransitionTo(CONFIRMED)).isTrue();
        assertThat(PENDING.canTransitionTo(CANCELLED)).isTrue();
    }

    @Test
    void rejectsTransitionsNotInTheDiagram() {
        assertThat(CONFIRMED.canTransitionTo(CANCELLED)).isFalse();
        assertThat(CANCELLED.canTransitionTo(CONFIRMED)).isFalse();
        assertThat(CONFIRMED.canTransitionTo(PENDING)).isFalse();
        assertThat(CANCELLED.canTransitionTo(PENDING)).isFalse();
    }

    @Test
    void rejectsNullTarget() {
        assertThatThrownBy(() -> PENDING.canTransitionTo(null)).isInstanceOf(NullPointerException.class);
    }

    @ParameterizedTest
    @EnumSource(OrderStatus.class)
    void isTerminalMatchesExpectedTerminalStates(final OrderStatus status) {
        final Set<OrderStatus> expectedTerminal = Set.of(CONFIRMED, CANCELLED);

        assertThat(status.isTerminal()).isEqualTo(expectedTerminal.contains(status));
    }

    @ParameterizedTest
    @EnumSource(OrderStatus.class)
    void everyNonTerminalStateHasAtLeastOneWayOut(final OrderStatus status) {
        if (!status.isTerminal()) {
            assertThat(status.allowedNextStates()).isNotEmpty();
        }
    }
}
