package com.processing.gateway.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.processing.common.dto.authorization.AuthorizationRequest;
import com.processing.gateway.metrics.GatewayMetrics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.mockito.ArgumentCaptor;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GatewayValidationWhiteBoxTest {

    @Test
    void shouldAcceptRequestWhenAllRequiredFieldsAreValid() {

        var validator = new TransactionRequestValidator();
        var request = validRequest().build();

        var thrown = catchThrowable(() -> validator.validate(request));

        assertThat(thrown).isNull();
    }


    @ParameterizedTest(name = "{0}: {2}")
    @MethodSource("invalidRequests")
    void shouldIdentifyInvalidFieldWhenRequestViolatesValidationRule(
            String id, AuthorizationRequest request, String field) {

        var validator = new TransactionRequestValidator();

        var thrown = catchThrowable(() -> validator.validate(request));

        assertThat(thrown).isInstanceOf(TransactionValidationException.class).hasMessageContaining(field);
    }

    static Stream<Arguments> invalidRequests() {
        return Stream.of(
                Arguments.of("WB-G02", validRequest().pan(null).build(), "pan"),
                Arguments.of("WB-G03", validRequest().amount(null).build(), "amount"),
                Arguments.of("WB-G04", validRequest().transmissionDateTime(null).build(), "transmissionDateTime"),
                Arguments.of("WB-G05", validRequest().mti(" ").build(), "mti"),
                Arguments.of("WB-G06", validRequest().acquirerId(" ").build(), "acquirerId"),
                Arguments.of("WB-G07", validRequest().mti("0200").build(), "mti"),
                Arguments.of("WB-G08", validRequest().pan("400000123456789").build(), "pan"),
                Arguments.of("WB-G09", validRequest().pan("40000012345678930").build(), "pan"),
                Arguments.of("WB-G10", validRequest().pan("40000012345678AB").build(), "pan"),
                Arguments.of("WB-G11", validRequest().amount(BigDecimal.ZERO).build(), "amount"),
                Arguments.of("WB-G12", validRequest().amount(new BigDecimal("-1")).build(), "amount"),
                Arguments.of("WB-G13", validRequest().currencyCode("64").build(), "currencyCode"),
                Arguments.of("WB-G14", validRequest().mcc("54AB").build(), "mcc"),
                Arguments.of("WB-G15", validRequest().mcc("541").build(), "mcc"),
                Arguments.of("WB-G16", validRequest().pan("").build(), "pan"));
    }


    @ParameterizedTest(name = "{0}")
    @MethodSource("filterRejections")
    void shouldWriteValidationErrorWithoutForwardingWhenTransactionIsInvalid(
            String id, AuthorizationRequest request, String field) throws Exception {

        var mapper = new ObjectMapper().findAndRegisterModules();
        var filter = filter(mapper);
        var chain = mock(GatewayFilterChain.class);
        var exchange = exchange("/api/transactions", mapper.writeValueAsString(request));

        filter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exchange.getResponse().getBodyAsString().block())
                .contains("VALIDATION_ERROR").contains(field);
        verify(chain, never()).filter(any());
    }

    static Stream<Arguments> filterRejections() {
        return Stream.of(Arguments.of("WB-G29", validRequest().pan("400000123456789").build(), "pan"),
                Arguments.of("WB-G30", validRequest().amount(BigDecimal.ZERO).build(), "amount"));
    }


    @Test
    void shouldForwardSameBodyWhenTransactionIsValid() throws Exception {

        var mapper = new ObjectMapper().findAndRegisterModules();
        var filter = filter(mapper);
        var chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());
        var body = mapper.writeValueAsString(validRequest().build());
        var exchange = exchange("/api/transactions", body);
        var forwarded = ArgumentCaptor.forClass(ServerWebExchange.class);

        filter.filter(exchange, chain).block();

        verify(chain).filter(forwarded.capture());
        var buffer = DataBufferUtils.join(forwarded.getValue().getRequest().getBody()).block();
        assertThat(buffer).isNotNull();
        assertThat(buffer.toString(StandardCharsets.UTF_8)).isEqualTo(body);
        DataBufferUtils.release(buffer);
    }


    @Test
    void shouldForwardWithoutParsingWhenPathIsNotTransactions() {

        var filter = filter(new ObjectMapper());
        var chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());
        var exchange = exchange("/api/cards", "not a transaction JSON");

        filter.filter(exchange, chain).block();

        verify(chain).filter(exchange);
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    private TransactionValidationFilter filter(ObjectMapper mapper) {
        return new TransactionValidationFilter(mapper, new TransactionRequestValidator(), mock(GatewayMetrics.class));
    }

    private MockServerWebExchange exchange(String path, String body) {
        return MockServerWebExchange.from(MockServerHttpRequest.post(path)
                .header("Content-Type", "application/json").body(body));
    }

    private static AuthorizationRequest.AuthorizationRequestBuilder validRequest() {
        return AuthorizationRequest.builder().mti("0100").stan("000001").pan("4000001234567893")
                .processingCode("000000").amount(BigDecimal.ONE).currencyCode("643")
                .transmissionDateTime(Instant.parse("2026-06-15T12:00:00Z")).terminalId("TERM0001")
                .terminalType("POS").merchantId("MERCH0000000001").mcc("5411").acquirerId("ACQ001");
    }
}
