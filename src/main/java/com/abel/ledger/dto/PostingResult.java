package com.abel.ledger.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PostingResult(
        UUID journalEntryId,
        String referenceId,
        String description,
        Instant createdAt,
        List<LedgerEntryResult> entries) {
}
