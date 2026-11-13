package com.processing.cardmanagement.services;

import com.processing.cardmanagement.events.CardServiceDeletionEvent;
import com.processing.cardmanagement.exceptions.*;
import com.processing.cardmanagement.models.*;
import com.processing.cardmanagement.options.OutboxOptions;
import com.processing.cardmanagement.repositories.*;
import com.processing.cardmanagement.services.retries.RetryServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CardInfraWhiteBoxTest {

    @Test
    void shouldSaveProcessedStatusWhenOutboxPublicationSucceeds() {

        var repository = mock(OutboxRepository.class);
        var rabbit = mock(RabbitTemplate.class);
        var processor = new OutboxEventProcessorImpl(repository, rabbit, new OutboxOptions(1000, 3));
        var event = pendingEvent();

        processor.processSingleEvent(event);

        verify(repository).save(argThat(saved -> saved.id().equals(event.id())
                && saved.status() == OutboxEventDataStatus.PROCESSED && saved.retryCount() == 0));
    }

    @Test
    void shouldKeepPendingStatusWhenRetryBudgetRemains() {

        var repository = mock(OutboxRepository.class);
        var rabbit = mock(RabbitTemplate.class);
        var processor = new OutboxEventProcessorImpl(repository, rabbit, new OutboxOptions(1000, 3));
        var event = pendingEvent();
        doThrow(new AmqpException("down"))
                .when(rabbit).convertAndSend(anyString(), anyString(), any(Object.class));

        processor.processSingleEvent(event);

        verify(repository).save(argThat(saved -> saved.id().equals(event.id())
                && saved.status() == OutboxEventDataStatus.PENDING && saved.retryCount() == 1));
    }

    @Test
    void shouldReturnValueWhenRetryOperationSucceedsImmediately() {

        var retry = new RetryServiceImpl();

        var value = retry.supply(0, () -> "saved");

        assertThat(value).isEqualTo("saved");
    }


    @Test
    void shouldReturnValueWhenRetryOperationRecovers() {

        var retry = new RetryServiceImpl();
        var operation = operation();
        when(operation.get()).thenThrow(new IllegalStateException("collision")).thenReturn("saved");

        var value = retry.supply(1, operation);

        assertThat(value).isEqualTo("saved");
    }


    @Test
    void shouldStopWithLastFailureWhenRetryBudgetIsExhausted() {

        var retry = new RetryServiceImpl();
        var operation = operation();
        var lastFailure = new IllegalStateException("second collision");
        when(operation.get()).thenThrow(new IllegalStateException("first collision"))
                .thenThrow(lastFailure).thenReturn("must not be reached");

        var thrown = catchThrowable(() -> retry.supply(1, operation));

        assertThat(thrown).isInstanceOf(OutOfRetriesException.class);
        assertThat(((OutOfRetriesException) thrown).getReason()).isSameAs(lastFailure);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"WB-C39, 4111111111111111, true", "WB-C40, 5555555555554444, true",
            "WB-C41, 4111111111111112, false", "WB-C42, 5555555555554445, false"})
    void shouldValidateChecksumWhenPanHasKnownCheckDigit(String id, String pan, boolean expected) {

        var validator = new LuhnValidator();

        var valid = validator.isValid(pan);

        assertThat(valid).isEqualTo(expected);
    }

    @Test
    void shouldReturnIssuerWhenBinExists() {

        var repository = mock(BinIssuerRepository.class);
        var service = new BinIssuerServiceImpl(repository);
        when(repository.findByBin("400000")).thenReturn(Optional.of(new BinIssuer("400000", "ISS001")));

        var issuer = service.getIssuerId("400000");

        assertThat(issuer).isEqualTo("ISS001");
    }

    @Test
    void shouldRejectIssuerLookupWhenBinDoesNotExist() {

        var repository = mock(BinIssuerRepository.class);
        var service = new BinIssuerServiceImpl(repository);
        when(repository.findByBin("999999")).thenReturn(Optional.empty());

        var thrown = catchThrowable(() -> service.getIssuerId("999999"));

        assertThat(thrown).isInstanceOf(BinNotFoundException.class);
    }


    @Test
    void shouldAvoidSaveWhenBinAlreadyExists() {

        var repository = mock(BinIssuerRepository.class);
        var service = new BinIssuerServiceImpl(repository);
        when(repository.existsByBin("400000")).thenReturn(true);

        var thrown = catchThrowable(() -> service.create("400000", "ISS001"));

        assertThat(thrown).isInstanceOf(BinAlreadyExistException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void shouldSaveIssuerWhenBinIsNew() {

        var repository = mock(BinIssuerRepository.class);
        var service = new BinIssuerServiceImpl(repository);
        when(repository.existsByBin("400000")).thenReturn(false);
        var issuer = new BinIssuer("400000", "ISS001");
        when(repository.save(issuer)).thenReturn(issuer);

        var result = service.create("400000", "ISS001");

        assertThat(result).isEqualTo(issuer);
        verify(repository).save(issuer);
    }

    @Test
    void shouldRejectDeleteWhenBinDoesNotExist() {

        var repository = mock(BinIssuerRepository.class);
        var service = new BinIssuerServiceImpl(repository);
        when(repository.existsByBin("999999")).thenReturn(false);

        var thrown = catchThrowable(() -> service.delete("999999"));

        assertThat(thrown).isInstanceOf(BinNotFoundException.class);
        verify(repository, never()).deleteByBin(anyString());
    }


    @Test
    void shouldDeleteBinWhenEntryExists() {

        var repository = mock(BinIssuerRepository.class);
        var service = new BinIssuerServiceImpl(repository);
        when(repository.existsByBin("400000")).thenReturn(true);

        service.delete("400000");

        verify(repository).deleteByBin("400000");
    }

    private CardOutboxEventData pendingEvent() {
        return new CardOutboxEventData(new UUID(0, 1), new CardServiceDeletionEvent("4000001234567893"),
                Instant.EPOCH, null, 0, null, OutboxEventDataStatus.PENDING);
    }

    @SuppressWarnings("unchecked")
    private Supplier<String> operation() {
        return mock(Supplier.class);
    }
}
