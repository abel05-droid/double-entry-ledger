package com.abel.ledger.api.controller;

import com.abel.ledger.api.dto.request.PostJournalEntryRequest;
import com.abel.ledger.api.dto.response.ErrorResponse;
import com.abel.ledger.api.dto.response.JournalEntryResponse;
import com.abel.ledger.api.mapper.LedgerApiMapper;
import com.abel.ledger.dto.PostingResult;
import com.abel.ledger.service.LedgerQueryService;
import com.abel.ledger.service.PostingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/v1/journal-entries")
@Tag(name = "Journal Entries", description = "Posting and retrieving balanced double-entry transactions")
public class JournalEntryController {

    private final PostingService postingService;
    private final LedgerQueryService ledgerQueryService;
    private final LedgerApiMapper mapper;

    public JournalEntryController(
            PostingService postingService, LedgerQueryService ledgerQueryService, LedgerApiMapper mapper) {
        this.postingService = postingService;
        this.ledgerQueryService = ledgerQueryService;
        this.mapper = mapper;
    }

    @PostMapping
    @Operation(summary = "Post a new journal entry",
            description = "Validates and atomically posts a balanced set of debit and credit entries. "
                    + "Replaying the same idempotencyKey with the same payload returns the original result.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Journal entry posted",
                content = @Content(schema = @Schema(implementation = JournalEntryResponse.class))),
        @ApiResponse(responseCode = "400", description = "Malformed request",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "409", description = "Idempotency key reused with a different payload",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "422",
                description = "Syntactically valid request that fails a business rule "
                        + "(unbalanced entries or currency mismatch)",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<JournalEntryResponse> postJournalEntry(
            @Valid @RequestBody PostJournalEntryRequest request) {
        PostingResult result = postingService.post(mapper.toServiceRequest(request));

        // PostingService returns its result before its own @Transactional
        // method commits, so Hibernate's @CreationTimestamp fields (which
        // populate at flush/commit, not at save()) can still be null on
        // `result`. By the time control returns here the transaction has
        // committed, so re-reading through LedgerQueryService gives the
        // fully-populated, persisted representation instead.
        PostingResult persisted = ledgerQueryService.getJournalEntry(result.journalEntryId());
        JournalEntryResponse body = mapper.toResponse(persisted);

        URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/v1/journal-entries/{id}")
                .buildAndExpand(result.journalEntryId())
                .toUri();

        return ResponseEntity.created(location)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a journal entry", description = "Returns a posted journal entry and its ledger entries.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Journal entry found",
                content = @Content(schema = @Schema(implementation = JournalEntryResponse.class))),
        @ApiResponse(responseCode = "404", description = "No journal entry with the given id",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public JournalEntryResponse getJournalEntry(
            @Parameter(description = "Journal entry id") @PathVariable UUID id) {
        return mapper.toResponse(ledgerQueryService.getJournalEntry(id));
    }
}
