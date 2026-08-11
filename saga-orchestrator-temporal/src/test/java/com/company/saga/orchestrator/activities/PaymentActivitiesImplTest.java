package com.company.saga.orchestrator.activities;

import io.temporal.failure.ApplicationFailure;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ExchangeFunction;

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
        activities = new PaymentActivitiesImpl(ExchangeFunctionStub.webClientFor(exchangeFunction));
        ExchangeFunctionStub.stubResponse(exchangeFunction, HttpStatus.CREATED);
    }

    @Test
    void requestPaymentPostsToThePaymentsEndpoint() {
        final UUID sagaId = UUID.randomUUID();

        activities.requestPayment(sagaId, new BigDecimal("49.99"));

        ExchangeFunctionStub.assertPostedTo(exchangeFunction, "/payments");
    }

    @Test
    void requestPaymentThrowsANonRetryableFailureWhenTheGatewayPermanentlyDeclinesThePayment() {
        ExchangeFunctionStub.stubResponse(exchangeFunction, HttpStatus.UNPROCESSABLE_CONTENT, "Payment declined for amount 15000.00");

        assertThatThrownBy(() -> activities.requestPayment(UUID.randomUUID(), new BigDecimal("15000.00")))
                .isInstanceOf(ApplicationFailure.class)
                .extracting(failure -> ((ApplicationFailure) failure).isNonRetryable())
                .isEqualTo(true);
    }

    @Test
    void refundPaymentPostsToTheRefundEndpointForTheGivenSagaId() {
        final UUID sagaId = UUID.randomUUID();
        ExchangeFunctionStub.stubResponse(exchangeFunction, HttpStatus.OK);

        activities.refundPayment(sagaId);

        ExchangeFunctionStub.assertPostedTo(exchangeFunction, "/payments/%s/refund".formatted(sagaId));
    }

    @Test
    void refundPaymentThrowsANonRetryableFailureWhenTheRefundIsPermanentlyUnrecoverable() {
        ExchangeFunctionStub.stubResponse(
                exchangeFunction, HttpStatus.UNPROCESSABLE_CONTENT, "Simulated unrecoverable refund failure for amount 750.00");

        assertThatThrownBy(() -> activities.refundPayment(UUID.randomUUID()))
                .isInstanceOf(ApplicationFailure.class)
                .extracting(failure -> ((ApplicationFailure) failure).isNonRetryable())
                .isEqualTo(true);
    }
}
