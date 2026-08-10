package com.company.saga.inventory.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Set;

import static com.company.saga.inventory.domain.ReservationStatus.CONFIRMED;
import static com.company.saga.inventory.domain.ReservationStatus.RELEASED;
import static com.company.saga.inventory.domain.ReservationStatus.RESERVED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReservationStatusTest {

    @Test
    void reservedCanBeConfirmedOrReleased() {
        assertThat(RESERVED.canTransitionTo(CONFIRMED)).isTrue();
        assertThat(RESERVED.canTransitionTo(RELEASED)).isTrue();
    }

    @Test
    void rejectsTransitionsNotInTheDiagram() {
        assertThat(CONFIRMED.canTransitionTo(RELEASED)).isFalse();
        assertThat(RELEASED.canTransitionTo(CONFIRMED)).isFalse();
        assertThat(CONFIRMED.canTransitionTo(RESERVED)).isFalse();
        assertThat(RELEASED.canTransitionTo(RESERVED)).isFalse();
    }

    @Test
    void rejectsNullTarget() {
        assertThatThrownBy(() -> RESERVED.canTransitionTo(null)).isInstanceOf(NullPointerException.class);
    }

    @ParameterizedTest
    @EnumSource(ReservationStatus.class)
    void isTerminalMatchesExpectedTerminalStates(final ReservationStatus status) {
        final Set<ReservationStatus> expectedTerminal = Set.of(CONFIRMED, RELEASED);

        assertThat(status.isTerminal()).isEqualTo(expectedTerminal.contains(status));
    }

    @ParameterizedTest
    @EnumSource(ReservationStatus.class)
    void everyNonTerminalStateHasAtLeastOneWayOut(final ReservationStatus status) {
        if (!status.isTerminal()) {
            assertThat(status.allowedNextStates()).isNotEmpty();
        }
    }
}
