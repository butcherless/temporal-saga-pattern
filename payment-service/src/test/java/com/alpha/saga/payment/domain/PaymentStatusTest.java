package com.alpha.saga.payment.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Set;

import static com.alpha.saga.payment.domain.PaymentStatus.COMPLETED;
import static com.alpha.saga.payment.domain.PaymentStatus.FAILED;
import static com.alpha.saga.payment.domain.PaymentStatus.PENDING;
import static com.alpha.saga.payment.domain.PaymentStatus.REFUNDED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentStatusTest {

    @Test
    void pendingCanCompleteOrFail() {
        assertThat(PENDING.canTransitionTo(COMPLETED)).isTrue();
        assertThat(PENDING.canTransitionTo(FAILED)).isTrue();
    }

    @Test
    void completedCanBeRefunded() {
        assertThat(COMPLETED.canTransitionTo(REFUNDED)).isTrue();
    }

    @Test
    void rejectsTransitionsNotInTheDiagram() {
        assertThat(FAILED.canTransitionTo(COMPLETED)).isFalse();
        assertThat(REFUNDED.canTransitionTo(COMPLETED)).isFalse();
        assertThat(COMPLETED.canTransitionTo(PENDING)).isFalse();
        assertThat(COMPLETED.canTransitionTo(FAILED)).isFalse();
        assertThat(REFUNDED.canTransitionTo(PENDING)).isFalse();
    }

    @Test
    void rejectsNullTarget() {
        assertThatThrownBy(() -> PENDING.canTransitionTo(null)).isInstanceOf(NullPointerException.class);
    }

    @ParameterizedTest
    @EnumSource(PaymentStatus.class)
    void isTerminalMatchesExpectedTerminalStates(final PaymentStatus status) {
        final Set<PaymentStatus> expectedTerminal = Set.of(FAILED, REFUNDED);

        assertThat(status.isTerminal()).isEqualTo(expectedTerminal.contains(status));
    }

    @ParameterizedTest
    @EnumSource(PaymentStatus.class)
    void everyNonTerminalStateHasAtLeastOneWayOut(final PaymentStatus status) {
        if (!status.isTerminal()) {
            assertThat(status.allowedNextStates()).isNotEmpty();
        }
    }
}
