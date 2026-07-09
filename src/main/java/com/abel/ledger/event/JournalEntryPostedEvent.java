package com.abel.ledger.event;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Raised by {@link com.abel.ledger.service.PostingService} after a
 * {@code JournalEntry} is newly persisted — never on a replayed idempotent
 * request, since {@code replay()} never raises this event. This is a plain
 * Java object with no framework or messaging dependency: it is published
 * through Spring's {@link org.springframework.context.ApplicationEventPublisher},
 * not any Kafka-specific API, so the service layer stays unaware of how (or
 * whether) anything downstream reacts to it.
 *
 * <p>{@code com.abel.ledger.kafka.LedgerEventPublisher} is the sole listener
 * that turns this into a Kafka message; it does so only after the
 * transaction that raised this event has committed (see
 * {@code docs/architecture.md}, "Event Publishing").
 */
public record JournalEntryPostedEvent(
        UUID journalEntryId,
        String referenceId,
        Instant postedAt,
        Set<UUID> affectedAccountIds) {
}
