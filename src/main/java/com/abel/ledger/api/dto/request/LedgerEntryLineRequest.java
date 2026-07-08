package com.abel.ledger.api.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

@Schema(description = "One debit or credit line within a journal entry posting request")
public record LedgerEntryLineRequest(

        @Schema(description = "Id of the account this line applies to", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "accountId is required")
        UUID accountId,

        @Schema(description = "Line amount; always positive regardless of debit/credit side", example = "100.00",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "amount is required")
        @Positive(message = "amount must be greater than zero")
        @Digits(integer = 15, fraction = 4, message = "amount must have at most 4 decimal places")
        BigDecimal amount,

        @Schema(description = "3-letter ISO 4217 currency code", example = "USD",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "currency is required")
        @Size(min = 3, max = 3, message = "currency must be a 3-letter ISO 4217 code")
        String currency) {
}
