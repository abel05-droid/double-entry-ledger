package com.abel.ledger.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One debit or credit line within a {@link PostingRequest}. Which side of
 * the transaction this represents (debit vs. credit) is determined by
 * which list of a {@code PostingRequest} it appears in, not by a field on
 * this type.
 */
public record LedgerEntryRequest(
        UUID accountId,
        BigDecimal amount,
        String currency) {
}
