package com.abel.ledger.kafka;

/**
 * Topic names for ledger domain events. Each topic name is hierarchical
 * ({@code <domain>.<entity>.<event-type>}) and carries an explicit version
 * suffix ({@code .v1}): a wire-incompatible schema change gets a new topic
 * ({@code .v2}) so existing consumers of {@code .v1} are never broken
 * out from under them. This is coarser than — and complements rather than
 * replaces — {@link JournalEntryPostedMessage#eventVersion()}, which allows
 * additive, backward-compatible evolution within a single topic. See
 * docs/architecture.md, "Event Publishing" / "Event Versioning".
 */
public final class LedgerKafkaTopics {

    public static final String JOURNAL_ENTRY_POSTED = "ledger.journal-entry.posted.v1";

    private LedgerKafkaTopics() {
    }
}
