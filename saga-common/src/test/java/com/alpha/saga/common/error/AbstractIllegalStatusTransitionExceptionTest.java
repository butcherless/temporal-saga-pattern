package com.alpha.saga.common.error;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AbstractIllegalStatusTransitionExceptionTest {

    private enum Light {
        ON,
        OFF
    }

    /** Stand-in for the three real {@code Illegal*TransitionException}s, all of which are this thin. */
    private static final class IllegalLightTransitionException extends AbstractIllegalStatusTransitionException {

        IllegalLightTransitionException(final Light from,
                final Light to) {
            super("light", from, to);
        }
    }

    @Test
    void buildsTheMessageFromTheAggregateNameAndBothStates() {
        final IllegalLightTransitionException exception = new IllegalLightTransitionException(Light.ON, Light.OFF);

        assertThat(exception.getMessage()).isEqualTo("Illegal light transition from ON to OFF");
        assertThat(exception).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void exposesFromAndTo() {
        final IllegalLightTransitionException exception = new IllegalLightTransitionException(Light.OFF, Light.ON);

        assertThat(exception.from()).isEqualTo(Light.OFF);
        assertThat(exception.to()).isEqualTo(Light.ON);
    }

    @Test
    void rejectsNullFromOrTo() {
        assertThatThrownBy(() -> new IllegalLightTransitionException(null, Light.ON))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("from");
        assertThatThrownBy(() -> new IllegalLightTransitionException(Light.ON, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("to");
    }
}
