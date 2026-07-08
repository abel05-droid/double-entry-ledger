package com.abel.ledger.api.controller;

import com.abel.ledger.api.dto.response.AccountBalanceResponse;
import com.abel.ledger.api.dto.response.ErrorResponse;
import com.abel.ledger.api.dto.response.LedgerEntryResponse;
import com.abel.ledger.api.dto.response.PagedResponse;
import com.abel.ledger.api.mapper.LedgerApiMapper;
import com.abel.ledger.dto.LedgerEntryResult;
import com.abel.ledger.service.BalanceService;
import com.abel.ledger.service.LedgerQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/accounts")
@Tag(name = "Accounts", description = "Account balance and ledger history queries")
public class AccountController {

    private final BalanceService balanceService;
    private final LedgerQueryService ledgerQueryService;
    private final LedgerApiMapper mapper;

    public AccountController(BalanceService balanceService, LedgerQueryService ledgerQueryService, LedgerApiMapper mapper) {
        this.balanceService = balanceService;
        this.ledgerQueryService = ledgerQueryService;
        this.mapper = mapper;
    }

    @GetMapping("/{id}/balance")
    @Operation(summary = "Get an account's current balance",
            description = "Balance is derived live from ledger entries; nothing is cached.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Balance computed",
                content = @Content(schema = @Schema(implementation = AccountBalanceResponse.class))),
        @ApiResponse(responseCode = "404", description = "No account with the given id",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public AccountBalanceResponse getBalance(@Parameter(description = "Account id") @PathVariable UUID id) {
        return mapper.toResponse(balanceService.getBalance(id));
    }

    @GetMapping("/{id}/ledger")
    @Operation(summary = "Get an account's ledger entries",
            description = "Paginated, newest first by default. Supports page, size, and sort query parameters.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Page of ledger entries"),
        @ApiResponse(responseCode = "404", description = "No account with the given id",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public PagedResponse<LedgerEntryResponse> getLedger(
            @Parameter(description = "Account id") @PathVariable UUID id,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        Page<LedgerEntryResult> page = ledgerQueryService.getLedgerEntries(id, pageable);
        return PagedResponse.from(page.map(mapper::toResponse));
    }
}
