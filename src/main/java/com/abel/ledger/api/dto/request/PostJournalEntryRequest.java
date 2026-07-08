package com.abel.ledger.api.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

@Schema(description = "Request to post one complete, balanced accounting transaction")
public record PostJournalEntryRequest(

        @Schema(description = "Client-supplied retry-safety token; replaying the same key with the same "
                + "payload returns the original result instead of creating a duplicate",
                example = "3f29b6b0-3c22-4e7a-9c1a-6e6f6a2b9b11", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "idempotencyKey is required")
        @Size(max = 255, message = "idempotencyKey must be at most 255 characters")
        String idempotencyKey,

        @Schema(description = "Business reference for the transaction (e.g. an invoice number); unique across "
                + "all journal entries", example = "INV-100245", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "referenceId is required")
        @Size(max = 128, message = "referenceId must be at most 128 characters")
        String referenceId,

        @Schema(description = "Free-text description of the transaction", example = "Invoice #100245 payment")
        @Size(max = 512, message = "description must be at most 512 characters")
        String description,

        @Schema(description = "One or more debit lines", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotEmpty(message = "At least one debit entry is required")
        @Valid
        List<LedgerEntryLineRequest> debitEntries,

        @Schema(description = "One or more credit lines", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotEmpty(message = "At least one credit entry is required")
        @Valid
        List<LedgerEntryLineRequest> creditEntries) {
}
