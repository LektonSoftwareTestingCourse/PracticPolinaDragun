package com.processing.cardmanagement.services;

import com.processing.cardmanagement.models.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

final class CardTestFixtures {
    private CardTestFixtures() {
    }

    static Card activeCard() {
        return card(CardStatus.ACTIVE);
    }

    static Card card(CardStatus status) {
        return new Card(new UUID(0, 1), "4000001234567893", "400000", "IVAN IVANOV",
                YearMonth.of(2029, 6), status, "643", new BigDecimal("100"), new BigDecimal("3000"),
                new BigDecimal("1000"), "ISS001", Instant.EPOCH);
    }

    static Reservation reservation(ReservationStatus status) {
        return new Reservation(new UUID(0, 2), activeCard().pan(), new BigDecimal("100"), "123456789012",
                status, Instant.EPOCH, Instant.EPOCH);
    }

    static ReservationRollback rollback(String pan, UUID reservationId) {
        return new ReservationRollback(new UUID(0, 3), reservationId, pan, new BigDecimal("100"),
                "123456789012", Instant.EPOCH);
    }


    static final class DirectTransactions implements TransactionRunner {
        @Override
        public void run(Runnable operation) {
            operation.run();
        }
        @Override
        public <T> T runSupplier(Supplier<T> operation) {
            return operation.get();
        }
        @Override
        public <T> T runSupplierWithNestedQuery(Function<TransactionRunner, T> supplier) {
            return supplier.apply(this);
        }
    }
}
