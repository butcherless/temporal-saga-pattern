package com.alpha.saga.web;

import com.alpha.saga.common.error.PermanentSagaException;
import com.alpha.saga.common.error.TemporarySagaException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Objects;

class AbstractSagaRestExceptionHandlerTest {

    /** Mirrors what each business service declares: {@code @RestControllerAdvice} + one extra handler of its own. */
    @RestControllerAdvice
    static class TestExceptionHandler extends AbstractSagaRestExceptionHandler {

        @ExceptionHandler(IllegalStateException.class)
        Mono<ResponseEntity<Object>> handleIllegalState(final IllegalStateException ex,
                final ServerWebExchange exchange) {
            return this.toProblemDetail(ex, HttpStatus.CONFLICT, exchange);
        }
    }

    @RestController
    static class TestController {

        @GetMapping("/boom/permanent")
        String permanent() {
            throw new PermanentSagaException("stock permanently insufficient");
        }

        @GetMapping("/boom/temporary")
        String temporary() {
            throw new TemporarySagaException("gateway timeout");
        }

        @GetMapping("/boom/illegal-state")
        String illegalState() {
            throw new IllegalStateException("bad state");
        }

        @PostMapping("/echo")
        String echo(@RequestBody final Body body) {
            return body.value();
        }

        record Body(String value) {
            Body {
                Objects.requireNonNull(value, "value must not be null");
                if (value.isBlank()) {
                    throw new IllegalArgumentException("value must not be blank");
                }
            }
        }
    }

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        this.client = WebTestClient.bindToController(new TestController())
                .controllerAdvice(new TestExceptionHandler())
                .build();
    }

    @Test
    void mapsPermanentSagaExceptionTo422ProblemDetail() {
        this.client.get().uri("/boom/permanent").exchange()
                .expectStatus().isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(422)
                .jsonPath("$.detail").isEqualTo("stock permanently insufficient");
    }

    @Test
    void mapsTemporarySagaExceptionTo503ProblemDetail() {
        this.client.get().uri("/boom/temporary").exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectBody()
                .jsonPath("$.status").isEqualTo(503)
                .jsonPath("$.detail").isEqualTo("gateway timeout");
    }

    @Test
    void letsASubclassHandlerReuseToProblemDetail() {
        this.client.get().uri("/boom/illegal-state").exchange()
                .expectStatus().isEqualTo(HttpStatus.CONFLICT)
                .expectBody()
                .jsonPath("$.status").isEqualTo(409)
                .jsonPath("$.detail").isEqualTo("bad state");
    }

    @Test
    void recoversTheValidationMessageFromAnInvalidRequestBody() {
        this.client.post().uri("/echo")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"value\":\" \"}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.detail").isEqualTo("value must not be blank");
    }
}
