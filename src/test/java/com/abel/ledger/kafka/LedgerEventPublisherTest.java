package com.abel.ledger.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.abel.ledger.event.JournalEntryPostedEvent;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.common.KafkaException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

@ExtendWith(MockitoExtension.class)
class LedgerEventPublisherTest {

    @Mock
    private KafkaTemplate<String, JournalEntryPostedMessage> kafkaTemplate;

    private MeterRegistry meterRegistry;
    private LedgerEventPublisher publisher;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        publisher = new LedgerEventPublisher(kafkaTemplate, meterRegistry);
    }

    @Test
    void sendsMessageKeyedByJournalEntryIdToTheConfiguredTopic() {
        UUID journalEntryId = UUID.randomUUID();
        UUID accountId1 = UUID.randomUUID();
        UUID accountId2 = UUID.randomUUID();
        Instant postedAt = Instant.now();
        JournalEntryPostedEvent event = new JournalEntryPostedEvent(
                journalEntryId, "REF-1", postedAt, Set.of(accountId1, accountId2));

        when(kafkaTemplate.send(anyString(), anyString(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));

        publisher.onJournalEntryPosted(event);

        ArgumentCaptor<JournalEntryPostedMessage> messageCaptor =
                ArgumentCaptor.forClass(JournalEntryPostedMessage.class);
        verify(kafkaTemplate).send(
                eq(LedgerKafkaTopics.JOURNAL_ENTRY_POSTED), eq(journalEntryId.toString()), messageCaptor.capture());

        JournalEntryPostedMessage message = messageCaptor.getValue();
        assertThat(message.eventId()).isNotNull();
        assertThat(message.eventVersion()).isEqualTo(1);
        assertThat(message.journalEntryId()).isEqualTo(journalEntryId);
        assertThat(message.referenceId()).isEqualTo("REF-1");
        assertThat(message.postedAt()).isEqualTo(postedAt);
        assertThat(message.affectedAccountIds()).containsExactlyInAnyOrder(accountId1, accountId2);

        assertThat(meterRegistry.get("ledger.kafka.publish.requests").tags("outcome", "success").counter().count())
                .isEqualTo(1.0);
        assertThat(meterRegistry.get("ledger.kafka.publish.duration").tags("outcome", "success").timer().count())
                .isEqualTo(1L);
    }

    @Test
    void doesNotPropagateWhenKafkaTemplateThrowsSynchronously() {
        JournalEntryPostedEvent event = new JournalEntryPostedEvent(
                UUID.randomUUID(), "REF-2", Instant.now(), Set.of(UUID.randomUUID()));

        when(kafkaTemplate.send(anyString(), anyString(), any())).thenThrow(new KafkaException("boom"));

        assertThatCode(() -> publisher.onJournalEntryPosted(event)).doesNotThrowAnyException();

        assertThat(meterRegistry.get("ledger.kafka.publish.requests").tags("outcome", "failure").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void doesNotPropagateWhenTheReturnedFutureCompletesExceptionally() {
        JournalEntryPostedEvent event = new JournalEntryPostedEvent(
                UUID.randomUUID(), "REF-3", Instant.now(), Set.of(UUID.randomUUID()));

        CompletableFuture<SendResult<String, JournalEntryPostedMessage>> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("broker unavailable"));
        when(kafkaTemplate.send(anyString(), anyString(), any())).thenReturn(failedFuture);

        assertThatCode(() -> publisher.onJournalEntryPosted(event)).doesNotThrowAnyException();

        assertThat(meterRegistry.get("ledger.kafka.publish.requests").tags("outcome", "failure").counter().count())
                .isEqualTo(1.0);
    }
}
