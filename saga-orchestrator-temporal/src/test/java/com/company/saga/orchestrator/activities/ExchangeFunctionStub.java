package com.company.saga.orchestrator.activities;

import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

/**
 * Shared plumbing for the three {@code *ActivitiesImpl} tests (Inventory/Payment/Order), each of
 * which stubs a Mockito {@link ExchangeFunction} rather than a real HTTP server and then asserts
 * the method/path the Activity posted to. Package-private: all three test classes already live in
 * this package.
 */
final class ExchangeFunctionStub {

    private ExchangeFunctionStub() {
    }

    static WebClient webClientFor(final ExchangeFunction exchangeFunction) {
        return WebClient.builder().exchangeFunction(exchangeFunction).build();
    }

    /**
     * Stubs the default response for a test's {@code @BeforeEach} — {@code lenient()} because some
     * tests override it with a second {@code stubResponse} call, which would otherwise trip
     * Mockito's strict-stubbing check on the unused default.
     */
    static void stubResponse(final ExchangeFunction exchangeFunction, final HttpStatus status) {
        lenient().when(exchangeFunction.exchange(any())).thenReturn(Mono.just(ClientResponse.create(status).build()));
    }

    static void stubResponse(final ExchangeFunction exchangeFunction, final HttpStatus status, final String body) {
        lenient().when(exchangeFunction.exchange(any())).thenReturn(Mono.just(ClientResponse.create(status).body(body).build()));
    }

    static void assertPostedTo(final ExchangeFunction exchangeFunction, final String expectedPath) {
        final ArgumentCaptor<ClientRequest> requestCaptor = ArgumentCaptor.forClass(ClientRequest.class);
        verify(exchangeFunction).exchange(requestCaptor.capture());
        assertThat(requestCaptor.getValue().method()).isEqualTo(HttpMethod.POST);
        assertThat(requestCaptor.getValue().url().getPath()).isEqualTo(expectedPath);
    }
}
