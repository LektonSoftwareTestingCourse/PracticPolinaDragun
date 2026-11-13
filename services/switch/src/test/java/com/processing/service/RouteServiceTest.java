package com.processing.service;

import com.processing.common.dto.authorization.AuthorizationRequest;
import com.processing.common.dto.authorization.AuthorizationResponse;
import com.processing.common.dto.transactionlogger.TransactionStatus;
import com.processing.config.SwitchProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RouteServiceTest {


    @Mock private AuthorizationClient authorization;
    @Mock private AcquiringFeeClient fees;
    @Mock private LoggerClient logger;
    private RouteService service;

    @BeforeEach
    void setUp() {
        var routing = new RoutingService(new SwitchProperties("test",
                Map.of("400000", "ISS001", "400001", "ISS002", "400002", "ISS003",
                        "400003", "ISS004", "400004", "ISS005"),
                null, null, null, null, null, null));
        service = new RouteService(routing, authorization, fees, logger);
    }


    @Test
    void shouldDeclineAndPublishWhenBinIsUnknown() {

        var request = request("9999990000000000", new BigDecimal("100000"));
        when(logger.log(any())).thenReturn(true);

        var response = service.route(request);

        assertThat(response.status()).isEqualTo("DECLINED");
        assertThat(response.responseCode()).isEqualTo("14");
        verify(authorization, never()).authorize(any());
        verify(logger).log(argThat(tx -> tx.status() == TransactionStatus.DECLINED));
    }


    @Test
    void shouldRollbackWhenApprovedTransactionCannotBePublished() {

        var request = request("4000001234567893", new BigDecimal("100000"));
        var approved = new AuthorizationResponse("0110", "000001", "123456789012",
                "ABC123", "00", "APPROVED", null, 0);
        when(authorization.authorize(any())).thenReturn(approved);
        when(logger.log(any())).thenReturn(false);

        var response = service.route(request);

        assertThat(response.status()).isEqualTo("DECLINED");
        assertThat(response.responseCode()).isEqualTo("96");
        verify(authorization).rollback(argThat(original -> original.pan().equals(request.pan())
                && original.amount().compareTo(request.amount()) == 0), eq(approved.rrn()));
    }


    @Test
    void shouldKeepOriginalDeclineWhenPublicationFails() {

        var request = request("4000001234567893", new BigDecimal("100000"));
        var declined = new AuthorizationResponse("0110", "000001", null, null,
                "51", "DECLINED", "INSUFFICIENT_FUNDS", 0);
        when(authorization.authorize(any())).thenReturn(declined);
        when(logger.log(any())).thenReturn(false);

        var response = service.route(request);

        assertThat(response).isEqualTo(declined);
        verify(authorization, never()).rollback(any(), any());
    }

    private AuthorizationRequest request(String pan, BigDecimal amount) {
        return new AuthorizationRequest("0100", "000001", pan, "000000", amount, "643",
                Instant.parse("2026-06-15T12:00:00Z"), "TERM0001", "POS",
                "MERCH0000000001", "5411", "ACQ001", "ISS001");
    }
}
