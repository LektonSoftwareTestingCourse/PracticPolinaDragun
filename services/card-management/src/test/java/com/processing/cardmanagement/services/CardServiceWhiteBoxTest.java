package com.processing.cardmanagement.services;

import com.processing.cardmanagement.events.*;
import com.processing.cardmanagement.exceptions.*;
import com.processing.cardmanagement.models.*;
import com.processing.cardmanagement.options.*;
import com.processing.cardmanagement.repositories.*;
import com.processing.cardmanagement.services.retries.RetryServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import static com.processing.cardmanagement.services.CardTestFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CardServiceWhiteBoxTest {
    private final CardRepository repository = mock(CardRepository.class);
    private final ReservationRepository reservations = mock(ReservationRepository.class);
    private final ReservationRollbackRepository rollbacks = mock(ReservationRollbackRepository.class);
    private final CardServiceSettings settings = mock(CardServiceSettings.class);
    private final CardServiceDefaults defaults = mock(CardServiceDefaults.class);
    private final CardEventNotifier events = mock(CardEventNotifier.class);
    private final PanGenerator pans = mock(PanGenerator.class);
    private final BinIssuerService issuers = mock(BinIssuerService.class);
    private final CardServiceImpl service = new CardServiceImpl(repository, reservations, rollbacks, settings, defaults,
            pans, events, issuers,
            new RetryServiceImpl(), new DirectTransactions());


    @Test
    void shouldReturnCardWhenRepositoryFindsPan() {

        var card = activeCard();
        when(repository.findByPan(card.pan())).thenReturn(Optional.of(card));

        var actual = service.getCard(card.pan());

        assertThat(actual).isEqualTo(card);
    }


    @Test
    void shouldRejectGetWhenRepositoryDoesNotFindPan() {

        when(repository.findByPan(anyString())).thenReturn(Optional.empty());

        var thrown = catchThrowable(() -> service.getCard(activeCard().pan()));

        assertThat(thrown).isInstanceOf(CardNotFoundException.class);
    }


    @ParameterizedTest(name = "{0}")
    @CsvSource(value = {"WB-C18|null|null|50|0", "WB-C19|5|5|5|5", "WB-C20|100|0|100|0"},
            delimiter = '|', nullValues = "null")
    void shouldUsePageArgumentsWhenPageIsWithinMaximum(
            String id, Integer limit, Long offset, int expectedLimit, long expectedOffset) {

        when(settings.maxPageLimit()).thenReturn(100);
        when(defaults.pageLimit()).thenReturn(50);
        when(defaults.pageOffset()).thenReturn(0L);
        var page = List.of(activeCard());
        when(repository.findCards(expectedLimit, expectedOffset, null, null, null, null, null)).thenReturn(page);

        var actual = service.getCards(limit, offset, null, null, null, null, null);

        assertThat(actual).isEqualTo(page);
    }


    @Test
    void shouldRejectPageWhenLimitExceedsConfiguredMaximum() {

        when(settings.maxPageLimit()).thenReturn(100);

        var thrown = catchThrowable(() -> service.getCards(101, 0L, null, null, null, null, null));

        assertThat(thrown).isInstanceOf(TooLargeLimitException.class);
    }


    @ParameterizedTest(name = "{0}")
    @CsvSource(value = {"WB-C22|null|null|null|null|ACTIVE|100|3000|1000",
            "WB-C23|BLOCKED|null|null|null|BLOCKED|100|3000|1000",
            "WB-C24|null|200|null|null|ACTIVE|200|3000|1000",
            "WB-C25|null|null|4000|null|ACTIVE|100|4000|1000",
            "WB-C26|null|null|null|2000|ACTIVE|100|3000|2000"}, delimiter = '|', nullValues = "null")
    void shouldPreserveUnspecifiedFieldsWhenCardIsPatched(String id, CardStatus status,
            BigDecimal daily, BigDecimal monthly, BigDecimal balance, CardStatus expectedStatus,
            BigDecimal expectedDaily, BigDecimal expectedMonthly, BigDecimal expectedBalance) {

        var card = activeCard();
        when(repository.findByPanForUpdate(card.pan())).thenReturn(Optional.of(card));
        when(repository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.patchCard(card.pan(), status, daily, monthly, balance);

        assertThat(result.status()).isEqualTo(expectedStatus);
        assertThat(result.dailyLimit()).isEqualByComparingTo(expectedDaily);
        assertThat(result.monthlyLimit()).isEqualByComparingTo(expectedMonthly);
        assertThat(result.availableBalance()).isEqualByComparingTo(expectedBalance);
        verify(repository).update(result);
        verify(events).notifyListeners(new CardServicePatchEvent(card.pan()));
    }


    @Test
    void shouldSaveReservationAndDebitBalanceWhenRrnIsUnique() {

        var card = activeCard();
        when(repository.findByPanForUpdate(card.pan())).thenReturn(Optional.of(card));
        when(reservations.isUnique("123456789012", card.pan())).thenReturn(true);
        when(reservations.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(repository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.reserve(card.pan(), new BigDecimal("100"), "123456789012");

        assertThat(result.availableBalance()).isEqualByComparingTo("900");
        verify(reservations).save(argThat(reservation -> reservation.pan().equals(card.pan())
                && reservation.rrn().equals("123456789012")
                && reservation.reservationAmount().compareTo(new BigDecimal("100")) == 0));
        verify(repository).update(result);
    }


    @Test
    void shouldAvoidDebitWhenReservationAlreadyExists() {

        var card = activeCard();
        when(repository.findByPanForUpdate(card.pan())).thenReturn(Optional.of(card));
        when(reservations.isUnique("123456789012", card.pan())).thenReturn(false);

        var thrown = catchThrowable(() -> service.reserve(card.pan(), BigDecimal.ONE, "123456789012"));

        assertThat(thrown).isInstanceOf(ReservationAlreadyExistsException.class);
        verify(repository, never()).update(any());
        verify(reservations, never()).save(any());
    }


    @Test
    void shouldSaveRollbackAndCreditBalanceWhenReservationExists() {

        var card = activeCard();
        var reservation = reservation(ReservationStatus.RESERVED);
        when(repository.findByPanForUpdate(card.pan())).thenReturn(Optional.of(card));
        when(reservations.findByRrnAndPanForUpdate(reservation.rrn(), card.pan())).thenReturn(Optional.of(reservation));
        when(rollbacks.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(repository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.rollback(card.pan(), new BigDecimal("100"), reservation.rrn());

        assertThat(result.availableBalance()).isEqualByComparingTo("1100");
        verify(rollbacks).save(argThat(rollback -> rollback.reservationId().equals(reservation.id())
                && rollback.rollbackAmount().compareTo(new BigDecimal("100")) == 0));
        verify(reservations).save(argThat(saved -> saved.id().equals(reservation.id())
                && saved.status() == ReservationStatus.ROLLED_BACK));
        verify(repository).update(result);
    }


    @Test
    void shouldAvoidCreditWhenReservationDoesNotExist() {

        var card = activeCard();
        when(repository.findByPanForUpdate(card.pan())).thenReturn(Optional.of(card));
        when(reservations.findByRrnAndPanForUpdate(anyString(), anyString())).thenReturn(Optional.empty());

        var thrown = catchThrowable(() -> service.rollback(card.pan(), BigDecimal.ONE, "123456789012"));

        assertThat(thrown).isInstanceOf(ReservationNotFoundException.class);
        verify(repository, never()).update(any());
        verify(rollbacks, never()).save(any());
    }


    @Test
    void shouldSaveDeletedStatusWhenCardIsDeleted() {

        var card = activeCard();
        when(repository.findByPanForUpdate(card.pan())).thenReturn(Optional.of(card));

        service.deleteCard(card.pan());

        verify(repository).update(argThat(saved -> saved.pan().equals(card.pan()) && saved.status() == CardStatus.DELETED));
        verify(events).notifyListeners(new CardServiceDeletionEvent(card.pan()));
    }


    @Test
    void shouldRejectBulkUpdateWhenNoSelectorIsProvided() {


        var thrown = catchThrowable(() -> service.bulkUpdateStatus(null, null, CardStatus.BLOCKED));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).update(any());
    }


    @Test
    void shouldRejectBulkUpdateWhenBothSelectorsAreProvided() {

        var bins = List.of("400000");
        var pans = List.of(activeCard().pan());

        var thrown = catchThrowable(() -> service.bulkUpdateStatus(bins, pans, CardStatus.BLOCKED));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).update(any());
    }


    @Test
    void shouldCreateActiveCardWhenBinIsKnown() {

        when(issuers.getIssuerId("400000")).thenReturn("ISS001");
        when(pans.generatePan("400000")).thenReturn("4000001234567893");
        when(settings.cardValidityPeriod()).thenReturn(3);
        when(repository.create(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var card = service.createCard("400000", "IVAN IVANOV", "643",
                new BigDecimal("100"), new BigDecimal("3000"), new BigDecimal("1000"));

        assertThat(card.pan()).isEqualTo("4000001234567893");
        assertThat(card.issuerId()).isEqualTo("ISS001");
        assertThat(card.status()).isEqualTo(CardStatus.ACTIVE);
        verify(repository).create(card);
        verify(events).notifyListeners(new CardServiceCreationEvent(1));
    }


    @Test
    void shouldAvoidCreationWhenIssuerCannotBeResolved() {

        when(issuers.getIssuerId("999999")).thenThrow(new BinNotFoundException("999999"));

        var thrown = catchThrowable(() -> service.createCard("999999", "IVAN IVANOV", "643",
                BigDecimal.ONE, BigDecimal.TEN, BigDecimal.TEN));

        assertThat(thrown).isInstanceOf(BinNotFoundException.class);
        verify(repository, never()).create(any());
    }


    @Test
    void shouldCreateBatchWhenRepositoryAcceptsAllCards() {

        when(issuers.getIssuerId("400000")).thenReturn("ISS001");
        when(pans.generatePan("400000")).thenReturn("4000001234567893");
        when(repository.createAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.createCards(List.of(draft()));

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().pan()).isEqualTo("4000001234567893");
        verify(repository).createAll(result);
        verify(events).notifyListeners(new CardServiceCreationEvent(1));
    }


    @Test
    void shouldRegeneratePanAndSaveIndividuallyWhenBatchCollides() {

        when(issuers.getIssuerId("400000")).thenReturn("ISS001");
        when(pans.generatePan("400000")).thenReturn("4000001234567893", "4000000000000002");
        when(repository.createAll(anyList())).thenThrow(new MassiveCardCreationCollisionException());
        when(repository.create(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.createCards(List.of(draft()));

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().pan()).isEqualTo("4000000000000002");
        verify(repository).create(result.getFirst());
    }


    @Test
    void shouldUpdateMatchingCardsWhenBulkSelectorContainsBins() {

        var bins = List.of("400000");
        when(repository.findCardsByBinsForUpdate(bins)).thenReturn(List.of(activeCard()));

        var updated = service.bulkUpdateStatus(bins, null, CardStatus.BLOCKED);

        assertThat(updated).isEqualTo(1);
        verify(repository).update(argThat(card -> card.pan().equals(activeCard().pan())
                && card.status() == CardStatus.BLOCKED));
    }


    @Test
    void shouldUpdateMatchingCardsWhenBulkSelectorContainsPans() {

        var selectedPans = List.of(activeCard().pan());
        when(repository.findCardsByPansForUpdate(selectedPans)).thenReturn(List.of(activeCard()));

        var updated = service.bulkUpdateStatus(null, selectedPans, CardStatus.BLOCKED);

        assertThat(updated).isEqualTo(1);
        verify(repository).update(argThat(card -> card.pan().equals(activeCard().pan())
                && card.status() == CardStatus.BLOCKED));
    }

    private CardDraft draft() {
        return new CardDraft("400000", "IVAN IVANOV", CardStatus.ACTIVE, "643",
                new BigDecimal("100"), new BigDecimal("3000"), new BigDecimal("1000"));
    }
}
