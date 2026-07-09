package com.abel.ledger.kafka;

import com.abel.ledger.event.JournalEntryPostedEvent;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Instant;
import java.util.UUID;
import net.logstash.logback.argument.StructuredArguments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Converts {@link JournalEntryPostedEvent} domain events into Kafka
 * messages. This is the sole place in the codebase that knows about Kafka
 * message shape, topic, and key — {@code PostingService} raises only the
 * framework-agnostic domain event and has no dependency on this class or
 * any Kafka type.
 *
 * <h2>Why {@code @TransactionalEventListener(phase = AFTER_COMMIT)}</h2>
 * This is the mechanism that satisfies "an event is published if and only
 * if the transaction actually committed." {@code PostingService} raises
 * the domain event via {@code ApplicationEventPublisher} as the last step
 * inside its {@code @Transactional} method, before that method returns —
 * i.e. before the surrounding transaction has actually committed. Spring
 * defers delivery of a {@code @TransactionalEventListener(AFTER_COMMIT)}
 * event via a registered {@code TransactionSynchronization}: the listener
 * method below is only invoked from that synchronization's
 * {@code afterCommit()} callback, which Spring only calls once the
 * transaction has actually committed successfully. If the transaction
 * instead rolls back — an unbalanced entry, a currency mismatch, or the
 * idempotency-key race described in {@code PostingService}'s own javadoc —
 * Spring discards the queued event without ever invoking this listener.
 * No manual rollback-detection code is needed; this is exactly what the
 * annotation is for, and it is why no event can ever escape for a posting
 * attempt that didn't actually persist.
 *
 * <p>Because the listener fires only after commit, and a sequential replay
 * of an idempotent request never reaches the line in
 * {@code PostingService.postWithinTransaction} that raises this event at
 * all (it returns via {@code replay()} instead), a duplicate event for the
 * same {@code JournalEntry} can never be published — not by a slow retry,
 * and not by two requests racing on the same {@code idempotencyKey} (the
 * loser's transaction rolls back, so its queued event, if any, is
 * discarded the same way any other rollback's would be).
 */
@Component
public class LedgerEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(LedgerEventPublisher.class);

    private final KafkaTemplate<String, JournalEntryPostedMessage> kafkaTemplate;
    private final MeterRegistry meterRegistry;

    public LedgerEventPublisher(
            KafkaTemplate<String, JournalEntryPostedMessage> ledgerEventKafkaTemplate, MeterRegistry meterRegistry) {
        this.kafkaTemplate = ledgerEventKafkaTemplate;
        this.meterRegistry = meterRegistry;
    }

    /**
     * {@code journalEntryId} is used as the Kafka message key so that every
     * message concerning a given {@code JournalEntry} — this "posted" event
     * today, and any future correction/reversal/amendment event for the
     * same entry — lands on the same partition and is therefore delivered
     * to consumers in the order it was produced. Since {@code journalEntryId}
     * is a randomly-generated UUID, keys (and therefore load) are spread
     * evenly across partitions; keying on something coarser and lower-
     * cardinality, like an account id, would instead concentrate a
     * high-activity account's events onto one partition and could throttle
     * its throughput relative to a partitioned, per-entry keying scheme.
     *
     * <p>Never throws: a Kafka publish failure — synchronous (e.g. producer
     * buffer exhaustion, serialization error) or asynchronous (broker
     * unreachable, timeout) — must never surface back through the
     * (already-committed) transaction to the caller of
     * {@code PostingService.post()}. See "Failure Handling" in
     * docs/architecture.md for why structured logging, backed by the
     * producer's own retry/idempotence configuration, is this phase's
     * complete answer to publish failures.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onJournalEntryPosted(JournalEntryPostedEvent event) {
        String key = event.journalEntryId().toString();
        UUID eventId = UUID.randomUUID();
        JournalEntryPostedMessage message = new JournalEntryPostedMessage(
                eventId,
                JournalEntryPostedMessage.CURRENT_EVENT_VERSION,
                event.journalEntryId(),
                event.referenceId(),
                event.postedAt(),
                event.affectedAccountIds().stream().sorted().toList());

        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            kafkaTemplate.send(LedgerKafkaTopics.JOURNAL_ENTRY_POSTED, key, message)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            recordFailure(sample);
                            logPublishFailure(event.journalEntryId(), eventId, ex);
                        } else {
                            recordSuccess(sample);
                            logPublishSuccess(event.journalEntryId(), eventId);
                        }
                    });
        } catch (Exception ex) {
            // kafkaTemplate.send() can itself throw synchronously (e.g. a
            // serialization failure) rather than only failing the future it
            // returns; caught here for the same reason the future's
            // exceptional completion is handled above.
            recordFailure(sample);
            logPublishFailure(event.journalEntryId(), eventId, ex);
        }
    }

    private void recordSuccess(Timer.Sample sample) {
        sample.stop(meterRegistry.timer("ledger.kafka.publish.duration", "outcome", "success"));
        meterRegistry.counter("ledger.kafka.publish.requests", "outcome", "success").increment();
    }

    private void recordFailure(Timer.Sample sample) {
        sample.stop(meterRegistry.timer("ledger.kafka.publish.duration", "outcome", "failure"));
        meterRegistry.counter("ledger.kafka.publish.requests", "outcome", "failure").increment();
    }

    private void logPublishSuccess(UUID journalEntryId, UUID eventId) {
        log.debug(
                "Published ledger event to Kafka {} {} {}",
                StructuredArguments.kv("journalEntryId", journalEntryId),
                StructuredArguments.kv("eventId", eventId),
                StructuredArguments.kv("topic", LedgerKafkaTopics.JOURNAL_ENTRY_POSTED));
    }

    /**
     * {@code correlationId} is deliberately not added here: this callback
     * may run on the Kafka producer's own I/O thread rather than the
     * original request thread once the send is genuinely asynchronous, and
     * SLF4J's MDC is thread-local — a correlation id set on the request
     * thread would not be visible here. See docs/architecture.md,
     * "Observability", for this known limitation.
     */
    private void logPublishFailure(UUID journalEntryId, UUID eventId, Throwable ex) {
        log.error(
                "Failed to publish ledger event to Kafka {} {} {} {} {} {}",
                StructuredArguments.kv("journalEntryId", journalEntryId),
                StructuredArguments.kv("eventId", eventId),
                StructuredArguments.kv("topic", LedgerKafkaTopics.JOURNAL_ENTRY_POSTED),
                StructuredArguments.kv("timestamp", Instant.now()),
                StructuredArguments.kv("exceptionClass", ex.getClass().getName()),
                StructuredArguments.kv("exceptionMessage", ex.getMessage()),
                ex);
    }
}
