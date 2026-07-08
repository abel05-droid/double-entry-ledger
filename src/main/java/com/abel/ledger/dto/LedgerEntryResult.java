package com.abel.ledger.dto;

import com.abel.ledger.domain.ledger.EntryType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record LedgerEntryResult(
        UUID id,
        UUID accountId,
        EntryType entryType,
        BigDecimal amount,
        String currency,
        Instant createdAt) {
}
