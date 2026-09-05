package com.alpha.saga.orchestrator.activities;

import io.temporal.failure.ApplicationFailure;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(MockitoExtension.class)
class PaymentActivitiesImplTest {

    @Mock
    private ExchangeFunction exchangeFunction;

    private PaymentActivitiesImpl activities;

    @BeforeEach
    void setUp() {
        this.activities = new PaymentActivitiesImpl(ExchangeFunctionStub.webClientFor(this.exchangeFunction));
        ExchangeFunctionStub.stubResponse(this.exchangeFunction, HttpStatus.CREATED);
    }

    @Test
    void requestPaymentPostsToThePaymentsEndpoint() {
        final UUID sagaId = UUID.randomUUID();

        this.activities.requestPayment(sagaId, new BigDecimal("49.99"));

        ExchangeFunctionStub.assertPostedTo(this.exchangeFunction, "/payments");
    }

    @Test
    void requestPaymentThrowsANonRetryableFailureWhenTheGatewayPermanentlyDeclinesThePayment() {
        ExchangeFunctionStub.stubResponse(this.exchangeFunction, HttpStatus.UNPROCESSABLE_CONTENT, "Payment declined for amount 15000.00");

        ExchangeFunctionStub.assertNonRetryable(() -> this.activities.requestPayment(UUID.randomUUID(), new BigDecimal("15000.00")));
    }

    /**
     * The non-422 half of {@code toActivityFailure}'s branch: a transient gateway fault must
     * pass through unchanged (still a plain {@link WebClientResponseException}, never wrapped as
     * non-retryable) so {@code OrderSagaWorkflowImpl}'s bounded {@code RetryOptions} still retries
     * it, the same as before this classification existed.
     */
    @Test
    void requestPaymentRethrowsUnchangedWhenTheFailureIsNotPermanent() {
        ExchangeFunctionStub.stubResponse(this.exchangeFunction, HttpStatus.SERVICE_UNAVAILABLE, "Payment gateway timeout for amount 2000.00");

        assertThatThrownBy(() -> this.activities.requestPayment(UUID.randomUUID(), new BigDecimal("2000.00")))
                .isInstanceOf(WebClientResponseException.class)
                .isNotInstanceOf(ApplicationFailure.class);
    }

    @Test
    void refundPaymentPostsToTheRefundEndpointForTheGivenSagaId() {
        final UUID sagaId = UUID.randomUUID();
        ExchangeFunctionStub.stubResponse(this.exchangeFunction, HttpStatus.OK);

        this.activities.refundPayment(sagaId);

        ExchangeFunctionStub.assertPostedTo(this.exchangeFunction, "/payments/%s/refund".formatted(sagaId));
    }

    @Test
    void refundPaymentThrowsANonRetryableFailureWhenTheRefundIsPermanentlyUnrecoverable() {
        ExchangeFunctionStub.stubResponse(
                this.exchangeFunction, HttpStatus.UNPROCESSABLE_CONTENT, "Simulated unrecoverable refund failure for amount 750.00");

        ExchangeFunctionStub.assertNonRetryable(() -> this.activities.refundPayment(UUID.randomUUID()));
    }
}
