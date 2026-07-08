package com.abel.ledger.api.dto.response;

import com.abel.ledger.domain.ledger.EntryType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "A single posted debit or credit line")
public record LedgerEntryResponse(

        @Schema(description = "Ledger entry id") UUID id,
        @Schema(description = "Account this line applies to") UUID accountId,
        @Schema(description = "Whether this line is a debit or a credit") EntryType entryType,
        @Schema(description = "Line amount; always positive", example = "100.00") BigDecimal amount,
        @Schema(description = "3-letter ISO 4217 currency code", example = "USD") String currency,
        @Schema(description = "When this line was posted") Instant createdAt) {
}
