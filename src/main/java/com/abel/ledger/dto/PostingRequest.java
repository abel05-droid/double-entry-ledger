package com.abel.ledger.dto;

import java.util.List;

/**
 * Represents one complete business transaction to be posted as a balanced
 * {@code JournalEntry}. {@code idempotencyKey} is a client-supplied
 * retry-safety token, distinct from {@code referenceId}, which is the
 * business identifier for the transaction itself.
 */
public record PostingRequest(
        String idempotencyKey,
        String referenceId,
        String description,
        List<LedgerEntryRequest> debitEntries,
        List<LedgerEntryRequest> creditEntries) {
}
