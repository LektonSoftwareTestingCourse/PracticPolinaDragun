package com.processing.cardmanagement.services;

import com.processing.cardmanagement.exceptions.InsufficientFundsException;
import com.processing.cardmanagement.exceptions.RollbackAlreadySatisfiedException;
import com.processing.cardmanagement.models.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import java.math.BigDecimal;
import java.util.UUID;
import static com.processing.cardmanagement.services.CardTestFixtures.*;
import static org.assertj.core.api.Assertions.*;

class CardRulesWhiteBoxTest {

    @ParameterizedTest(name = "{0}")
    @CsvSource({"WB-C01, 999", "WB-C02, 1000"})
    void shouldStartReservationWhenAmountDoesNotExceedBalance(String id, BigDecimal amount) {

        var card = activeCard();

        var reservation = card.startReservation(amount, "123456789012");

        assertThat(reservation.pan()).isEqualTo(card.pan());
        assertThat(reservation.reservationAmount()).isEqualByComparingTo(amount);
        assertThat(reservation.status()).isEqualTo(ReservationStatus.RESERVED);
    }

    @Test
    void shouldRejectReservationWhenAmountExceedsBalance() {
        var card = activeCard();

        var thrown = catchThrowable(() -> card.startReservation(new BigDecimal("1001"), "123456789012"));

        assertThat(thrown).isInstanceOf(InsufficientFundsException.class);
    }

    @ParameterizedTest(name = "WB-C04–07: {0}")
    @EnumSource(value = CardStatus.class, names = {"INACTIVE", "BLOCKED", "EXPIRED", "DELETED"})
    void shouldRejectReservationWhenCardStatusIsNotActive(CardStatus status) {

        var card = card(status);

        var thrown = catchThrowable(() -> card.startReservation(BigDecimal.ONE, "123456789012"));

        assertThat(thrown).isInstanceOf(IllegalStateException.class);
    }


    @Test
    void shouldRejectBalanceChangeWhenReservationBelongsToAnotherPan() {

        var card = activeCard();
        var reservation = new Reservation("4000011234567890", BigDecimal.ONE, "123456789012");

        var thrown = catchThrowable(() -> card.withReservation(reservation));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class);
    }


    @Test
    void shouldRejectBalanceChangeWhenRollbackBelongsToAnotherPan() {

        var card = activeCard();
        var rollback = rollback("4000011234567890", new UUID(0, 2));

        var thrown = catchThrowable(() -> card.withRollback(rollback));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"WB-C10, -1, 3000", "WB-C11, 100, -1", "WB-C12, 101, 100"})
    void shouldRejectUpdateWhenLimitsViolateInvariant(String id, BigDecimal daily, BigDecimal monthly) {

        var card = activeCard();

        var thrown = catchThrowable(() -> card.withData(CardStatus.ACTIVE, daily, monthly, card.availableBalance()));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectDeletionWhenCardIsAlreadyDeleted() {

        var card = card(CardStatus.DELETED);

        var thrown = catchThrowable(card::deleted);

        assertThat(thrown).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldRejectRollbackWhenReservationIsAlreadyRolledBack() {

        var reservation = reservation(ReservationStatus.ROLLED_BACK);

        var thrown = catchThrowable(() -> reservation.startRollback(BigDecimal.ONE));

        assertThat(thrown).isInstanceOf(RollbackAlreadySatisfiedException.class);
    }

    @Test
    void shouldRejectRollbackMarkerWhenReservationIdDiffers() {

        var reservation = reservation(ReservationStatus.RESERVED);
        var rollback = rollback(reservation.pan(), new UUID(0, 999));

        var thrown = catchThrowable(() -> reservation.rolledBack(rollback));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class);
    }
}
