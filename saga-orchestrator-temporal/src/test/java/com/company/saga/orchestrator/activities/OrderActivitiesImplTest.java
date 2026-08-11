package com.company.saga.orchestrator.activities;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ExchangeFunction;

import java.util.UUID;

@ExtendWith(MockitoExtension.class)
class OrderActivitiesImplTest {

    @Mock
    private ExchangeFunction exchangeFunction;

    private OrderActivitiesImpl activities;

    @BeforeEach
    void setUp() {
        activities = new OrderActivitiesImpl(ExchangeFunctionStub.webClientFor(exchangeFunction));
        ExchangeFunctionStub.stubResponse(exchangeFunction, HttpStatus.OK);
    }

    @Test
    void confirmOrderPostsToTheConfirmEndpointForTheGivenSagaId() {
        final UUID sagaId = UUID.randomUUID();

        activities.confirmOrder(sagaId);

        ExchangeFunctionStub.assertPostedTo(exchangeFunction, "/orders/%s/confirm".formatted(sagaId));
    }

    @Test
    void cancelOrderPostsToTheCancelEndpointForTheGivenSagaId() {
        final UUID sagaId = UUID.randomUUID();

        activities.cancelOrder(sagaId);

        ExchangeFunctionStub.assertPostedTo(exchangeFunction, "/orders/%s/cancel".formatted(sagaId));
    }
}
