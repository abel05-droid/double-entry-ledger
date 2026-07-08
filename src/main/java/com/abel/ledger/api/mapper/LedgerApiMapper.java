package com.abel.ledger.api.mapper;

import com.abel.ledger.api.dto.request.LedgerEntryLineRequest;
import com.abel.ledger.api.dto.request.PostJournalEntryRequest;
import com.abel.ledger.api.dto.response.AccountBalanceResponse;
import com.abel.ledger.api.dto.response.JournalEntryResponse;
import com.abel.ledger.api.dto.response.LedgerEntryResponse;
import com.abel.ledger.dto.AccountBalance;
import com.abel.ledger.dto.LedgerEntryRequest;
import com.abel.ledger.dto.LedgerEntryResult;
import com.abel.ledger.dto.PostingRequest;
import com.abel.ledger.dto.PostingResult;
import org.springframework.stereotype.Component;

/**
 * Translates between the API layer's request/response DTOs and the
 * service layer's DTOs. Keeps the public API contract independent of
 * {@link com.abel.ledger.service.PostingService} and
 * {@link com.abel.ledger.service.BalanceService}'s internal shapes.
 */
@Component
public class LedgerApiMapper {

    public PostingRequest toServiceRequest(PostJournalEntryRequest request) {
        return new PostingRequest(
                request.idempotencyKey(),
                request.referenceId(),
                request.description(),
                request.debitEntries().stream().map(this::toServiceEntry).toList(),
                request.creditEntries().stream().map(this::toServiceEntry).toList());
    }

    private LedgerEntryRequest toServiceEntry(LedgerEntryLineRequest entry) {
        return new LedgerEntryRequest(entry.accountId(), entry.amount(), entry.currency());
    }

    public JournalEntryResponse toResponse(PostingResult result) {
        return new JournalEntryResponse(
                result.journalEntryId(),
                result.referenceId(),
                result.description(),
                result.createdAt(),
                result.entries().stream().map(this::toResponse).toList());
    }

    public LedgerEntryResponse toResponse(LedgerEntryResult entry) {
        return new LedgerEntryResponse(
                entry.id(), entry.accountId(), entry.entryType(), entry.amount(), entry.currency(),
                entry.createdAt());
    }

    public AccountBalanceResponse toResponse(AccountBalance balance) {
        return new AccountBalanceResponse(balance.accountId(), balance.currency(), balance.balance());
    }
}
