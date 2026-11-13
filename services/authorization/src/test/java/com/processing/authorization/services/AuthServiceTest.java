package com.processing.authorization.services;

import com.processing.authorization.client.BinLookupClient;
import com.processing.authorization.client.CardManagementClient;
import com.processing.authorization.events.AuthorizationEventNotifier;
import com.processing.authorization.exceptions.CardNotFoundException;
import com.processing.authorization.repositories.LimitUsageRepository;
import com.processing.common.dto.authorization.AuthorizationRequest;
import com.processing.common.dto.cardmanagement.CardModel;
import com.processing.common.dto.cardmanagement.CardModelStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {
    @Mock private LimitUsageRepository limits;
    @Mock private AuthorizationEventNotifier events;
    @Mock private BinLookupClient binLookup;
    @Mock private CardManagementClient cards;
    private AuthServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AuthServiceImpl(limits, events, binLookup, cards);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"Aut-class:07, 100001, 1000001, 100000",
            "Aut-class:13, 100000, 1000000, 100001"})
    void shouldApproveAndReserveWhenBinLookupIsUnavailable(
            String caseId, BigDecimal daily, BigDecimal monthly, BigDecimal balance) {

        var card = activeCard(YearMonth.of(2026, 6), daily, monthly, balance);
        var request = request(card.pan(), new BigDecimal("100000"));
        when(cards.getCard(card.pan())).thenReturn(card);
        when(binLookup.getIssuerId(card.pan())).thenReturn(Optional.empty());

        when(limits.upsertLimitUsage(eq(card.pan()), any(), eq(request.amount()), eq(daily), eq(monthly)))
                .thenReturn(1);
        when(limits.fetchRrnBlock()).thenReturn(1L);
        var inputTime = Instant.now();

        var response = service.authorize(request, inputTime);

        assertThat(response.status()).isEqualTo("APPROVED");
        assertThat(response.responseCode()).isEqualTo("00");
        verify(cards).reserve(eq(request.amount()), eq(response.rrn()), eq(card.pan()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("insufficientBalanceCases")
    void shouldDeclineWithoutReserveWhenAmountExceedsBalance(String caseId, CardModel card) {
        var request = request(card.pan(), new BigDecimal("100000"));
        when(cards.getCard(card.pan())).thenReturn(card);
        when(binLookup.getIssuerId(card.pan())).thenReturn(Optional.of("ISS001"));
        var inputTime = Instant.now();

        var response = service.authorize(request, inputTime);

        assertThat(response.status()).isEqualTo("DECLINED");
        assertThat(response.responseCode()).isEqualTo("51");
        verify(cards, never()).reserve(any(), any(), any());
    }

    @Test
    void shouldDeclineWhenCardDoesNotExist() {

        var request = request("4000001234567893", new BigDecimal("100000"));
        when(cards.getCard(request.pan())).thenThrow(new CardNotFoundException("not found"));
        var inputTime = Instant.now();

        var response = service.authorize(request, inputTime);

        assertThat(response.status()).isEqualTo("DECLINED");
        assertThat(response.responseCode()).isEqualTo("14");
    }

    @ParameterizedTest(name = "{0}: {1}")
    @CsvSource({"Aut-class:11, INACTIVE, 05, CARD_INACTIVE",
            "Aut-class:14, EXPIRED, 54, CARD_EXPIRED",
            "Aut-class:18, BLOCKED, 05, CARD_BLOCKED"})
    void shouldDeclineWithoutReserveWhenCardIsNotActive(
            String caseId, CardModelStatus status, String code, String reason) {

        var card = card(status, YearMonth.of(2029, 6), new BigDecimal("100001"),
                new BigDecimal("1000001"), new BigDecimal("100001"));
        var request = request(card.pan(), new BigDecimal("100000"));
        when(cards.getCard(card.pan())).thenReturn(card);
        when(binLookup.getIssuerId(card.pan())).thenReturn(Optional.of("ISS001"));
        var inputTime = Instant.now();

        var response = service.authorize(request, inputTime);

        assertThat(response.status()).isEqualTo("DECLINED");
        assertThat(response.responseCode()).isEqualTo(code);
        assertThat(response.declineReason()).isEqualTo(reason);
        verify(cards, never()).reserve(any(), any(), any());
    }

    @Test
    void shouldDeclineWithoutReserveWhenExpiryMonthHasPassed() {

        var card = activeCard(YearMonth.of(2026, 5), new BigDecimal("100001"),
                new BigDecimal("1000001"), new BigDecimal("100001"));
        var request = request(card.pan(), new BigDecimal("100000"));
        when(cards.getCard(card.pan())).thenReturn(card);
        when(binLookup.getIssuerId(card.pan())).thenReturn(Optional.of("ISS001"));
        var inputTime = Instant.now();

        var response = service.authorize(request, inputTime);

        assertThat(response.status()).isEqualTo("DECLINED");
        assertThat(response.responseCode()).isEqualTo("54");
        verify(cards, never()).reserve(any(), any(), any());
    }

    private static Stream<Arguments> insufficientBalanceCases() {
        return Stream.of(
                Arguments.of("Aut-class:08", activeCard(YearMonth.of(2029, 6),
                        new BigDecimal("100000"), new BigDecimal("1000001"), new BigDecimal("99999"))),
                Arguments.of("Aut-class:15", activeCard(YearMonth.of(2026, 6),
                        new BigDecimal("100001"), new BigDecimal("1000000"), new BigDecimal("99999"))),
                Arguments.of("Aut-triple:07", activeCard(YearMonth.of(2029, 6),
                        new BigDecimal("100001"), new BigDecimal("1000001"), new BigDecimal("99999"))),
                Arguments.of("Aut-triple:08", activeCard(YearMonth.of(2029, 6),
                        new BigDecimal("100000"), new BigDecimal("1000000"), new BigDecimal("99999"))));
    }

    private static CardModel activeCard(YearMonth expiry, BigDecimal daily, BigDecimal monthly, BigDecimal balance) {
        return card(CardModelStatus.ACTIVE, expiry, daily, monthly, balance);
    }

    private static CardModel card(CardModelStatus status, YearMonth expiry,
                                  BigDecimal daily, BigDecimal monthly, BigDecimal balance) {
        return new CardModel(new UUID(0, 1), "4000001234567893", "400000", "IVAN IVANOV",
                expiry, status, "643", daily, monthly, balance, "ISS001",
                Instant.parse("2026-06-01T00:00:00Z"));
    }

    private AuthorizationRequest request(String pan, BigDecimal amount) {
        return new AuthorizationRequest("0100", "000001", pan, "000000", amount, "643",
                Instant.parse("2026-06-15T12:00:00Z"), "TERM0001", "POS",
                "MERCH0000000001", "5411", "ACQ001", "ISS001");
    }
}
