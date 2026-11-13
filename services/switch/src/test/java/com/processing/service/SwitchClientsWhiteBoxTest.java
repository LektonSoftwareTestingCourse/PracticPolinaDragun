package com.processing.service;

import com.processing.common.dto.authorization.AuthorizationRequest;
import com.processing.config.CircuitBreakerFactory;
import com.processing.config.RetryFactory;
import com.processing.config.SwitchProperties;
import com.processing.exception.AuthorizationException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class SwitchClientsWhiteBoxTest {
    private MockRestServiceServer server;
    private RestClient http;
    private CircuitBreaker breaker;
    private AuthorizationClient authorization;

    @BeforeEach
    void setUp() {
        var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        http = builder.build();
        breaker = CircuitBreakerFactory.authorizationCircuitBreaker(properties(List.of(1L)));
        var retry = Retry.of("test", RetryConfig.custom().maxAttempts(1).waitDuration(Duration.ZERO).build());
        authorization = new AuthorizationClient(properties(List.of(1L)), http, retry, breaker);
    }


    @ParameterizedTest(name = "{0}")
    @CsvSource({"WB-S24, APPROVED", "WB-S25, DECLINED"})
    void shouldReturnBusinessResponseWhenAuthorizationStatusIsKnown(String id, String status) {

        server.expect(requestTo("http://authorization/api/internal/authorize"))
                .andRespond(withSuccess("{\"status\":\"" + status + "\"}", MediaType.APPLICATION_JSON));

        var response = authorization.authorize(request());

        assertThat(response.status()).isEqualTo(status);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(value = {"WB-S26|{\"status\":\"UNKNOWN\"}", "WB-S27|''"}, delimiter = '|')
    void shouldFailAuthorizationWhenResponseCannotRepresentDecision(String id, String body) {

        server.expect(requestTo("http://authorization/api/internal/authorize"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        var thrown = catchThrowable(() -> authorization.authorize(request()));

        assertThat(thrown).isInstanceOf(AuthorizationException.class);
    }

    @Test
    void shouldFailAuthorizationWhenUpstreamReturnsServerErrorWithoutDecision() {

        server.expect(requestTo("http://authorization/api/internal/authorize"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        var thrown = catchThrowable(() -> authorization.authorize(request()));

        assertThat(thrown).isInstanceOf(AuthorizationException.class);
    }

    @Test
    void shouldFailFastWhenAuthorizationCircuitIsOpen() {

        breaker.transitionToOpenState();

        var thrown = catchThrowable(() -> authorization.authorize(request()));

        assertThat(thrown).isInstanceOf(AuthorizationException.class).hasMessageContaining("Circuit breaker open");
    }


    @ParameterizedTest(name = "{0}")
    @CsvSource({"WB-S30, APPROVED", "WB-S31, DECLINED"})
    void shouldReturnRollbackResponseWhenUpstreamReturnsKnownStatus(String id, String status) {

        server.expect(requestTo("http://authorization/api/internal/rollback"))
                .andRespond(withSuccess("{\"status\":\"" + status + "\"}", MediaType.APPLICATION_JSON));

        var response = authorization.rollback(request(), "123456789012");

        assertThat(response.status()).isEqualTo(status);
    }

    @Test
    void shouldReturnNullWhenRollbackResponseIsEmpty() {

        server.expect(requestTo("http://authorization/api/internal/rollback"))
                .andRespond(withSuccess("", MediaType.APPLICATION_JSON));

        var response = authorization.rollback(request(), "123456789012");

        assertThat(response).isNull();
    }

    @Test
    void shouldReturnFeeWhenAcquirerProvidesAmount() {

        var client = new MerchantAcquirerClient(properties(List.of(1L)), http);
        server.expect(requestTo("http://merchant/api/simulator/merchant/fee"))
                .andRespond(withSuccess("{\"acquirerFee\":1.25}", MediaType.APPLICATION_JSON));

        var fee = client.fetchAcquiringFee(Instant.EPOCH, "000001", "4000001234567893", "TERM0001", BigDecimal.ONE);

        assertThat(fee).isEqualByComparingTo("1.25");
    }

    @Test
    void shouldReturnNullFeeWhenAcquirerIsUnavailable() {

        var client = new MerchantAcquirerClient(properties(List.of(1L)), http);
        server.expect(requestTo("http://merchant/api/simulator/merchant/fee")).andRespond(withServerError());

        var fee = client.fetchAcquiringFee(Instant.EPOCH, "000001", "4000001234567893", "TERM0001", BigDecimal.ONE);

        assertThat(fee).isNull();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("retryIntervals")
    void shouldCalculateBackoffWhenRetryConfigurationHasBoundaryValues(
            String id, List<Long> delays, int failedAttempts, long expected) {

        var properties = properties(delays);

        var interval = RetryFactory.authorizationRetry(properties).getRetryConfig()
                .getIntervalFunction().apply(failedAttempts);

        assertThat(interval).isEqualTo(expected);
    }

    static Stream<Arguments> retryIntervals() {
        return Stream.of(Arguments.of("WB-S35", null, 1, 1L),
                Arguments.of("WB-S36", List.of(), 1, 1L),
                Arguments.of("WB-S37", List.of(10L, 20L), 3, 20L),
                Arguments.of("WB-S38", List.of(-1L), 1, 1L));
    }

    private SwitchProperties properties(List<Long> delays) {
        return new SwitchProperties("test", Map.of("400000", "ISS001"), "http://authorization", "http://logger",
                "http://merchant", null, new SwitchProperties.RetryProperties(3, delays),
                new SwitchProperties.CircuitBreakerSection(
                        new SwitchProperties.CircuitBreakerProperties(50, 10, 5, 10000)));
    }

    private AuthorizationRequest request() {
        return new AuthorizationRequest("0100", "000001", "4000001234567893", "000000", BigDecimal.ONE, "643",
                Instant.EPOCH, "TERM0001", "POS", "MERCH0000000001", "5411", "ACQ001", "ISS001");
    }
}
