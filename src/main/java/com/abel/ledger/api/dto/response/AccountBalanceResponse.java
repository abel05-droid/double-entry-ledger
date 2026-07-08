package com.abel.ledger.api.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.UUID;

@Schema(description = "An account's current derived balance")
public record AccountBalanceResponse(

        @Schema(description = "Account id") UUID accountId,
        @Schema(description = "3-letter ISO 4217 currency code", example = "USD") String currency,
        @Schema(description = "Current balance, derived live from ledger entries", example = "1250.00")
        BigDecimal balance) {
}
