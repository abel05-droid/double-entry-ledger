package com.abel.ledger.api.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "Login credentials")
public record LoginRequest(

        @Schema(description = "Username", example = "admin", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "username is required")
        String username,

        @Schema(description = "Password", example = "admin123", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "password is required")
        String password) {
}
