package com.abel.ledger.api.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "Successful login result")
public record LoginResponse(

        @Schema(description = "Signed JWT; send as 'Authorization: Bearer <token>' on subsequent requests")
        String token,

        @Schema(description = "Token expiry")
        Instant expiresAt) {
}
