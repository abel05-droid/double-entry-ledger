package com.abel.ledger.api.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.springframework.data.domain.Page;

/**
 * API-layer pagination envelope. Decouples the public API contract from
 * Spring Data's {@link Page}, which is never returned directly from a
 * controller.
 */
@Schema(description = "A page of results")
public record PagedResponse<T>(

        @Schema(description = "The page's content") List<T> content,
        @Schema(description = "Zero-based page number", example = "0") int page,
        @Schema(description = "Requested page size", example = "20") int size,
        @Schema(description = "Total number of elements across all pages", example = "42") long totalElements,
        @Schema(description = "Total number of pages", example = "3") int totalPages,
        @Schema(description = "Whether a next page exists") boolean hasNext) {

    public static <T> PagedResponse<T> from(Page<T> page) {
        return new PagedResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.hasNext());
    }
}
