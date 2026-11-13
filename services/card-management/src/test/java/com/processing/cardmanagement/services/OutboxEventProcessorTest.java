package com.processing.cardmanagement.services;

import com.processing.cardmanagement.events.CardServiceDeletionEvent;
import com.processing.cardmanagement.models.CardOutboxEventData;
import com.processing.cardmanagement.models.OutboxEventDataStatus;
import com.processing.cardmanagement.options.OutboxOptions;
import com.processing.cardmanagement.repositories.OutboxRepository;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OutboxEventProcessorTest {

    @Test
    void shouldSaveFailedEventWhenLastPublicationAttemptFails() {

        var repository = mock(OutboxRepository.class);
        var rabbit = mock(RabbitTemplate.class);
        var processor = new OutboxEventProcessorImpl(repository, rabbit, new OutboxOptions(1000, 3));
        var event = pendingEventOnLastAttempt();
        doThrow(new AmqpException("broker unavailable"))
                .when(rabbit).convertAndSend(anyString(), anyString(), any(Object.class));

        var thrown = catchThrowable(() -> processor.processSingleEvent(event));

        assertThat(thrown).isNull();
        verify(repository).save(argThat(saved -> saved.id().equals(event.id())
                && saved.retryCount() == 3 && saved.status() == OutboxEventDataStatus.FAILED));
    }

    private CardOutboxEventData pendingEventOnLastAttempt() {
        return new CardOutboxEventData(new UUID(0, 1),
                new CardServiceDeletionEvent("4000001234567893"),
                Instant.parse("2026-06-15T12:00:00Z"), null, 2, null, OutboxEventDataStatus.PENDING);
    }
}
