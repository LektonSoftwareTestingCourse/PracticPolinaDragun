package com.processing.service;

import com.processing.common.dto.authorization.*;
import com.processing.common.dto.transactionlogger.TransactionRequest;
import com.processing.config.SwitchProperties;
import com.processing.exception.AuthorizationException;
import com.processing.exception.UnknownBinException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SwitchWhiteBoxTest {

    @ParameterizedTest(name = "{0}")
    @CsvSource(value = {"WB-S01|<null>|<null>", "WB-S02|'   '|'   '", "WB-S03|TERM|TERM0000",
            "WB-S04|TERM0001|TERM0001", "WB-S05|TERM00012|TERM0001"}, delimiter = '|', nullValues = "<null>")
    void shouldNormalizeTerminalWhenIdentifierHasBoundaryLength(String id, String input, String expected) {

        var actual = AuthorizationRequestNormalizer.normalizeTerminalId(input);

        assertThat(actual).isEqualTo(expected);
    }


    @ParameterizedTest(name = "{0}")
    @CsvSource(value = {"WB-S06|<null>|<null>", "WB-S07|'   '|'   '",
            "WB-S08|SHOP|SHOP00000000000", "WB-S09|MERCH0000000001|MERCH0000000001",
            "WB-S10|MERCH00000000001|MERCH0000000001",
            "WB-S11|OTHER00000000001|OTHER0000000000",
            "WB-S12|MERCH10000000001|MERCH1000000000"}, delimiter = '|', nullValues = "<null>")
    void shouldNormalizeMerchantWhenIdentifierMatchesFormatBranch(String id, String input, String expected) {

        var actual = AuthorizationRequestNormalizer.normalizeMerchantId(input);

        assertThat(actual).isEqualTo(expected);
    }


    @ParameterizedTest(name = "{0}")
    @CsvSource({"WB-S13, 4000011234567890, ISS002", "WB-S14, 4000021234567890, ISS003",
            "WB-S15, 4000031234567890, ISS004", "WB-S16, 4000041234567890, ISS005"})
    void shouldReturnConfiguredIssuerWhenAnotherApprovedBinIsUsed(String id, String pan, String issuer) {

        var routing = routing();

        var actual = routing.getIssuerIdByPan(pan);

        assertThat(actual).isEqualTo(issuer);
    }


    @ParameterizedTest(name = "{0}")
    @CsvSource(value = {"WB-S17|<null>", "WB-S18|9999991234567890"}, delimiter = '|', nullValues = "<null>")
    void shouldRejectRoutingWhenPanCannotResolveIssuer(String id, String pan) {

        var routing = routing();

        var thrown = catchThrowable(() -> routing.getIssuerIdByPan(pan));

        assertThat(thrown).isInstanceOf(UnknownBinException.class);
    }


    @Test
    void shouldPublishDeclineWhenAuthorizationIsUnavailable() {

        var auth = mock(AuthorizationClient.class);
        var logger = mock(LoggerClient.class);
        var service = new RouteService(routing(), auth, mock(AcquiringFeeClient.class), logger);
        when(auth.authorize(any())).thenThrow(new AuthorizationException("000001", 1, "down"));
        when(logger.log(any())).thenReturn(true);

        var response = service.route(request());

        assertThat(response.status()).isEqualTo("DECLINED");
        assertThat(response.responseCode()).isEqualTo("05");
        verify(logger).log(argThat(tx -> "DECLINED".equals(tx.status().name())));
        verify(auth, never()).rollback(any(), any());
    }


    @ParameterizedTest(name = "{0}")
    @CsvSource({"WB-S20, APPROVED, 00", "WB-S21, DECLINED, 05"})
    void shouldReturnSystemErrorWhenRollbackReturnsKnownStatus(String id, String status, String code) {

        var auth = mock(AuthorizationClient.class);
        var logger = mock(LoggerClient.class);
        var service = new RouteService(routing(), auth, mock(AcquiringFeeClient.class), logger);
        when(auth.authorize(any())).thenReturn(new AuthorizationResponse("0110", "000001", "123456789012",
                "ABC123", "00", "APPROVED", null, 0));
        when(logger.log(any())).thenReturn(false);
        when(auth.rollback(any(), any())).thenReturn(new RollbackResponse("123456789012", code, status, null, 0));

        var response = service.route(request());

        assertThat(response.status()).isEqualTo("DECLINED");
        assertThat(response.responseCode()).isEqualTo("96");
        verify(auth).rollback(argThat(original -> original.pan().equals(request().pan())
                && original.amount().compareTo(request().amount()) == 0), eq("123456789012"));
    }

    @Test
    void shouldConfirmPublicationWhenRabbitAcceptsMessage() {

        var rabbit = mock(RabbitTemplate.class);
        var logger = new LoggerClient(rabbit);
        var tx = mock(TransactionRequest.class);

        var accepted = logger.log(tx);

        assertThat(accepted).isTrue();
        verify(rabbit).convertAndSend(eq("smp.transactions"), eq("transaction.log"), same(tx));
    }

    @Test
    void shouldRejectPublicationWhenRabbitThrowsAmqpException() {

        var rabbit = mock(RabbitTemplate.class);
        var logger = new LoggerClient(rabbit);
        doThrow(new AmqpException("down"))
                .when(rabbit).convertAndSend(anyString(), anyString(), any(Object.class));

        var accepted = logger.log(mock(TransactionRequest.class));

        assertThat(accepted).isFalse();
    }

    private RoutingService routing() {
        return new RoutingService(new SwitchProperties("test", Map.of("400000", "ISS001", "400001", "ISS002",
                "400002", "ISS003", "400003", "ISS004", "400004", "ISS005"),
                null, null, null, null, null, null));
    }

    private AuthorizationRequest request() {
        return new AuthorizationRequest("0100", "000001", "4000001234567893", "000000", BigDecimal.ONE, "643",
                Instant.parse("2026-06-15T12:00:00Z"), "TERM0001", "POS", "MERCH0000000001", "5411", "ACQ001", null);
    }
}
