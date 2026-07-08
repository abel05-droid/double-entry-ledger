package com.abel.ledger.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record AccountBalance(
        UUID accountId,
        String currency,
        BigDecimal balance) {
}
