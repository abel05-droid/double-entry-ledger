package com.abel.ledger.api.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "Standard error response shape for all failed requests")
public record ErrorResponse(

        @Schema(description = "When the error occurred") Instant timestamp,
        @Schema(description = "HTTP status code", example = "409") int status,
        @Schema(description = "HTTP status reason phrase", example = "Conflict") String error,
        @Schema(description = "Human-readable error message") String message,
        @Schema(description = "Request path that produced the error", example = "/api/v1/journal-entries")
        String path) {
}
