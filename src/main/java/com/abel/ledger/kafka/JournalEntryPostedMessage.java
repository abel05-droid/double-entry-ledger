package com.abel.ledger.kafka;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The JSON payload published to {@link LedgerKafkaTopics#JOURNAL_ENTRY_POSTED}.
 * This is the public wire contract for the event, distinct from
 * {@link com.abel.ledger.event.JournalEntryPostedEvent} (the in-process
 * domain event it's built from): {@code eventId} and {@code eventVersion}
 * are publishing-mechanism concerns, not business facts, so they belong
 * here rather than on the domain event.
 *
 * <p>Deliberately excludes {@code LedgerEntry} details and monetary
 * amounts. This event exists to tell downstream systems "a journal entry
 * was posted, here's its id" — a notification, not a data feed. A consumer
 * that needs the actual debit/credit lines and amounts is expected to call
 * back into the REST API ({@code GET /api/v1/journal-entries/{id}}) using
 * {@code journalEntryId}, which keeps the ledger's REST API as the single
 * source of truth for financial data and this event stream free of
 * amounts that would otherwise need independent correctness guarantees.
 *
 * <h2>Versioning</h2>
 * {@code eventVersion} is part of the public contract of this event, not
 * an implementation detail. It is {@code 1} for every event this phase
 * produces. A future change that alters the meaning of an existing field,
 * removes a field, or otherwise breaks a consumer parsing this schema
 * MUST introduce {@code eventVersion 2} (and, if the change is wire
 * -incompatible, a new {@code .v2} topic per {@link LedgerKafkaTopics}) —
 * existing fields must never be silently repurposed. Purely additive,
 * backward-compatible fields may be added without a version bump, since
 * JSON consumers are expected to ignore unknown fields.
 */
public record JournalEntryPostedMessage(
        UUID eventId,
        int eventVersion,
        UUID journalEntryId,
        String referenceId,
        Instant postedAt,
        List<UUID> affectedAccountIds) {

    public static final int CURRENT_EVENT_VERSION = 1;
}
