package com.company.saga.orchestrator.activities;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderActivitiesImplTest {

    @Mock
    private ExchangeFunction exchangeFunction;

    private OrderActivitiesImpl activities;

    @BeforeEach
    void setUp() {
        final WebClient webClient = WebClient.builder().exchangeFunction(exchangeFunction).build();
        activities = new OrderActivitiesImpl(webClient);
        when(exchangeFunction.exchange(any())).thenReturn(Mono.just(ClientResponse.create(HttpStatus.OK).build()));
    }

    @Test
    void confirmOrderPostsToTheConfirmEndpointForTheGivenSagaId() {
        final UUID sagaId = UUID.randomUUID();

        activities.confirmOrder(sagaId);

        final ArgumentCaptor<ClientRequest> requestCaptor = ArgumentCaptor.forClass(ClientRequest.class);
        verify(exchangeFunction).exchange(requestCaptor.capture());
        assertThat(requestCaptor.getValue().method()).isEqualTo(HttpMethod.POST);
        assertThat(requestCaptor.getValue().url().getPath()).isEqualTo("/orders/" + sagaId + "/confirm");
    }
}
