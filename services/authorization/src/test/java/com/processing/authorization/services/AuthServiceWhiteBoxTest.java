package com.processing.authorization.services;

import com.processing.authorization.client.BinLookupClient;
import com.processing.authorization.client.CardManagementClient;
import com.processing.authorization.events.AuthorizationEventNotifier;
import com.processing.authorization.exceptions.*;
import com.processing.authorization.repositories.LimitUsageRepository;
import com.processing.common.dto.authorization.*;
import com.processing.common.dto.cardmanagement.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.client.ResourceAccessException;
import java.math.BigDecimal;
import java.time.*;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceWhiteBoxTest {
    @Mock private LimitUsageRepository limits;
    @Mock private CardManagementClient cards;
    @Mock private BinLookupClient bins;
    @Mock private AuthorizationEventNotifier events;
    private AuthServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AuthServiceImpl(limits, events, bins, cards);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("readFailures")
    void shouldDeclineWhenCardReadFails(String id, RuntimeException failure, String code, String reason) {
        var request = request("2026-06-15T12:00:00Z");
        when(cards.getCard(request.pan())).thenThrow(failure);
        var started = Instant.now();

        var response = service.authorize(request, started);

        assertThat(response.status()).isEqualTo("DECLINED");
        assertThat(response.responseCode()).isEqualTo(code);
        assertThat(response.declineReason()).isEqualTo(reason);
        verify(cards, never()).reserve(any(), any(), any());
    }

    static Stream<Arguments> readFailures() {
        return Stream.of(
                Arguments.of("WB-A01", new InvalidGetCardRequestException("bad"), "14", "CARD_NOT_FOUND"),
                Arguments.of("WB-A02", new ServiceUnavailableException("down"), "96", "SERVICE_UNAVAILABLE"),
                Arguments.of("WB-A03", new ResourceAccessException("timeout"), "96", "SERVICE_UNAVAILABLE"),
                Arguments.of("WB-A04", new InternalCardManagerException("error"), "96", "SERVICE_UNAVAILABLE"),
                Arguments.of("WB-A05", new PaymentRequiredException("blocked"), "05", "CARD_BLOCKED"),
                Arguments.of("WB-A06", new GetCardException("error"), "96", "SERVICE_UNAVAILABLE"),
                Arguments.of("WB-A07", new IllegalStateException("unknown"), "05", "UNKNOWN_REASON"));
    }


    @ParameterizedTest(name = "{0}")
    @MethodSource("reserveFailures")
    void shouldDeclineWhenReservationFails(String id, RuntimeException failure, String code, String reason) {
        var request = request("2026-06-15T12:00:00Z");
        activeCardIsFound();
        limitsAccept();
        when(limits.fetchRrnBlock()).thenReturn(1L);
        doThrow(failure).when(cards).reserve(any(), any(), any());
        var started = Instant.now();

        var response = service.authorize(request, started);

        assertThat(response.status()).isEqualTo("DECLINED");
        assertThat(response.responseCode()).isEqualTo(code);
        assertThat(response.declineReason()).isEqualTo(reason);
    }

    static Stream<Arguments> reserveFailures() {
        return Stream.of(
                Arguments.of("WB-A08", new CardNotFoundException("gone"), "14", "CARD_NOT_FOUND"),
                Arguments.of("WB-A09", new ServiceUnavailableException("down"), "96", "SERVICE_UNAVAILABLE"),
                Arguments.of("WB-A10", new ResourceAccessException("timeout"), "96", "SERVICE_UNAVAILABLE"),
                Arguments.of("WB-A11", new InternalCardManagerException("error"), "96", "SERVICE_UNAVAILABLE"),
                Arguments.of("WB-A12", new InvalidReserveRequestException("bad"), "14", "CARD_NOT_FOUND"),
                Arguments.of("WB-A13", new InsufficientFundsException("race"), "51", "INSUFFICIENT_FUNDS"),
                Arguments.of("WB-A14", new ReserveException("error"), "96", "RESERVATION_FAILED"),
                Arguments.of("WB-A15", new IllegalStateException("unknown"), "05", "UNKNOWN_REASON"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rollbackFailures")
    void shouldDeclineRollbackWhenCmsRejectsCommand(String id, RuntimeException failure, String code, String reason) {

        var request = rollbackRequest();
        doThrow(failure).when(cards).rollback(request);
        var started = Instant.now();

        var response = service.rollback(request, started);

        assertThat(response.status()).isEqualTo("DECLINED");
        assertThat(response.responseCode()).isEqualTo(code);
        assertThat(response.declineReason()).isEqualTo(reason);
        assertThat(response.rrn()).isEqualTo(request.rrn());
    }

    static Stream<Arguments> rollbackFailures() {
        return Stream.of(
                Arguments.of("WB-A16", new CardNotFoundException("gone"), "14", "TRANSACTION_NOT_FOUND"),
                Arguments.of("WB-A17", new InvalidRollbackRequestException("bad"), "14", "TRANSACTION_NOT_FOUND"),
                Arguments.of("WB-A18", new ServiceUnavailableException("down"), "96", "SERVICE_UNAVAILABLE"),
                Arguments.of("WB-A19", new ResourceAccessException("timeout"), "96", "SERVICE_UNAVAILABLE"),
                Arguments.of("WB-A20", new InternalCardManagerException("error"), "96", "SERVICE_UNAVAILABLE"),
                Arguments.of("WB-A21", new RollbackConflictException("repeat"), "05", "ALREADY_ROLLED_BACK"),
                Arguments.of("WB-A22", new RollbackFailureException("error"), "96", "ROLLBACK_FAILED"),
                Arguments.of("WB-A23", new IllegalStateException("unknown"), "05", "UNKNOWN_REASON"));
    }

    @Test
    void shouldApproveRollbackWhenCmsAcceptsCommand() {

        var request = rollbackRequest();
        var started = Instant.now();

        var response = service.rollback(request, started);

        assertThat(response.status()).isEqualTo("APPROVED");
        assertThat(response.responseCode()).isEqualTo("00");
        verify(cards).rollback(request);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"WB-A25, 0, false", "WB-A26, 1, true"})
    void shouldReturnUpdateOutcomeWhenRepositoryReportsAffectedRows(String id, int rows, boolean expected) {

        var card = activeCard(CardModelStatus.ACTIVE);
        when(limits.upsertLimitUsage(anyString(), any(), any(), any(), any())).thenReturn(rows);

        var accepted = service.checkAndUpdateLimits(card, BigDecimal.ONE, LocalDate.of(2026, 6, 15));

        assertThat(accepted).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("databaseFailures")
    void shouldDeclineWithoutReserveWhenLimitStorageFails(String id, RuntimeException failure) {
        activeCardIsFound();
        when(limits.upsertLimitUsage(anyString(), any(), any(), any(), any())).thenThrow(failure);
        var request = request("2026-06-15T12:00:00Z");
        var started = Instant.now();
        var response = service.authorize(request, started);
        assertThat(response.responseCode()).isEqualTo("96");
        assertThat(response.declineReason()).isEqualTo("DB_UNAVAILABLE");
        verify(cards, never()).reserve(any(), any(), any());
    }

    static Stream<Arguments> databaseFailures() {
        return Stream.of(Arguments.of("WB-A27", new IllegalStateException("db down")),
                Arguments.of("WB-A28", new DuplicateKeyException("collision on both attempts")));
    }

    @Test
    void shouldApproveWhenLimitUpdateRecoversAfterDuplicateKey() {
        activeCardIsFound();
        when(limits.upsertLimitUsage(anyString(), any(), any(), any(), any()))
                .thenThrow(new DuplicateKeyException("race")).thenReturn(1);
        when(limits.fetchRrnBlock()).thenReturn(1L);
        var request = request("2026-06-15T12:00:00Z");
        var started = Instant.now();

        var response = service.authorize(request, started);

        assertThat(response.status()).isEqualTo("APPROVED");
        verify(cards).reserve(eq(request.amount()), eq(response.rrn()), eq(request.pan()));
    }

    @Test
    void shouldDeclineWhenRetriedLimitUpdateRejectsAmount() {
        activeCardIsFound();
        when(limits.upsertLimitUsage(anyString(), any(), any(), any(), any()))
                .thenThrow(new DuplicateKeyException("race")).thenReturn(0);
        var request = request("2026-06-15T12:00:00Z");
        var started = Instant.now();

        var response = service.authorize(request, started);

        assertThat(response.responseCode()).isEqualTo("61");
        verify(cards, never()).reserve(any(), any(), any());
    }


    @Test
    void shouldDeclineWhenCardStatusIsMissing() {

        when(cards.getCard(anyString())).thenReturn(activeCard(null));
        when(bins.getIssuerId(anyString())).thenReturn(Optional.empty());
        var request = request("2026-06-15T12:00:00Z");
        var started = Instant.now();

        var response = service.authorize(request, started);

        assertThat(response.declineReason()).isEqualTo("UNKNOWN_REASON");
        assertThat(response.status()).isEqualTo("DECLINED");
        verify(cards, never()).reserve(any(), any(), any());
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"WB-A32, 2026-06-29T12:00:00Z", "WB-A33, 2026-06-30T23:59:59Z"})
    void shouldApproveWhenTransactionIsWithinExpiryMonth(String id, String timestamp) {

        activeCardIsFound();
        limitsAccept();
        when(limits.fetchRrnBlock()).thenReturn(1L);
        var request = request(timestamp);
        var started = Instant.now();

        var response = service.authorize(request, started);

        assertThat(response.status()).isEqualTo("APPROVED");
        verify(cards).reserve(eq(request.amount()), eq(response.rrn()), eq(request.pan()));
    }

    @Test
    void shouldDeclineWhenNextMonthBeginsInUtc() {

        activeCardIsFound();
        var request = request("2026-07-01T00:00:00Z");
        var started = Instant.now();

        var response = service.authorize(request, started);

        assertThat(response.responseCode()).isEqualTo("54");
        verify(cards, never()).reserve(any(), any(), any());
    }

    @Test
    void shouldReturnSixAlphanumericCharactersWhenAuthCodeIsGenerated() {
        var code = service.generateAuthCode();

        assertThat(code).matches("[A-Z0-9]{6}");
    }

    @Test
    void shouldReturnTwelveDigitsWhenRrnIsGenerated() {
        when(limits.fetchRrnBlock()).thenReturn(1L);

        var rrn = service.generateRRN();

        assertThat(rrn).matches("[0-9]{12}");
    }

    @Test
    void shouldReturnDifferentRrnWhenBlockAlreadyContainsIssuedNumber() {
        when(limits.fetchRrnBlock()).thenReturn(1L);
        var previous = service.generateRRN();

        var next = service.generateRRN();

        assertThat(next).isNotEqualTo(previous);
        verify(limits).fetchRrnBlock();
    }


    @Test
    void shouldReserveNextBlockWhenCurrentRrnBlockIsExhausted() {
        when(limits.fetchRrnBlock()).thenReturn(1L, 2L);
        exhaustRrnBlock();
        var next = service.generateRRN();

        assertThat(next).endsWith("00002000");
        verify(limits, times(2)).fetchRrnBlock();
    }

    private void exhaustRrnBlock() {
        IntStream.range(0, 1000).forEach(ignored -> service.generateRRN());
    }

    private void activeCardIsFound() {
        when(cards.getCard(anyString())).thenReturn(activeCard(CardModelStatus.ACTIVE));
        when(bins.getIssuerId(anyString())).thenReturn(Optional.of("ISS001"));
    }

    private void limitsAccept() {
        when(limits.upsertLimitUsage(anyString(), any(), any(), any(), any())).thenReturn(1);
    }

    private CardModel activeCard(CardModelStatus status) {
        return new CardModel(new UUID(0, 1), "4000001234567893", "400000", "IVAN IVANOV",
                YearMonth.of(2026, 6), status, "643", new BigDecimal("1000000"),
                new BigDecimal("30000000"), new BigDecimal("1000000"), "ISS001", Instant.EPOCH);
    }

    private AuthorizationRequest request(String timestamp) {
        return new AuthorizationRequest("0100", "000001", "4000001234567893", "000000", BigDecimal.ONE,
                "643", Instant.parse(timestamp), "TERM0001", "POS", "MERCH0000000001", "5411", "ACQ001", "ISS001");
    }

    private RollbackRequest rollbackRequest() {
        return new RollbackRequest("123456789012", "4000001234567893", BigDecimal.ONE);
    }
}
