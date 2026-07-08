package com.abel.ledger.api.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(description = "A posted journal entry and its ledger entries")
public record JournalEntryResponse(

        @Schema(description = "Journal entry id") UUID id,
        @Schema(description = "Business reference for the transaction", example = "INV-100245") String referenceId,
        @Schema(description = "Free-text description of the transaction") String description,
        @Schema(description = "When this journal entry was posted") Instant createdAt,
        @Schema(description = "The debit and credit lines that make up this transaction")
        List<LedgerEntryResponse> entries) {
}
