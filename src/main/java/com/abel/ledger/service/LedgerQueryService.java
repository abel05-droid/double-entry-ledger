package com.abel.ledger.service;

import com.abel.ledger.domain.journal.JournalEntry;
import com.abel.ledger.domain.ledger.LedgerEntry;
import com.abel.ledger.dto.LedgerEntryResult;
import com.abel.ledger.dto.PostingResult;
import com.abel.ledger.exception.AccountNotFoundException;
import com.abel.ledger.exception.JournalEntryNotFoundException;
import com.abel.ledger.repository.AccountRepository;
import com.abel.ledger.repository.JournalEntryRepository;
import com.abel.ledger.repository.LedgerEntryRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only lookups over posted journal entries and ledger entries.
 * Complements {@link PostingService} (writes) and {@link BalanceService}
 * (aggregate balance reads) without modifying either.
 */
@Service
public class LedgerQueryService {

    private final JournalEntryRepository journalEntryRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final AccountRepository accountRepository;

    public LedgerQueryService(
            JournalEntryRepository journalEntryRepository,
            LedgerEntryRepository ledgerEntryRepository,
            AccountRepository accountRepository) {
        this.journalEntryRepository = journalEntryRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.accountRepository = accountRepository;
    }

    @Transactional(readOnly = true)
    public PostingResult getJournalEntry(UUID journalEntryId) {
        JournalEntry journalEntry = journalEntryRepository.findById(journalEntryId)
                .orElseThrow(() -> new JournalEntryNotFoundException(
                        "No journal entry found with id " + journalEntryId));
        List<LedgerEntry> entries = ledgerEntryRepository.findByJournalEntryId(journalEntryId);

        List<LedgerEntryResult> entryResults = entries.stream()
                .map(e -> new LedgerEntryResult(
                        e.getId(), e.getAccount().getId(), e.getEntryType(), e.getAmount(), e.getCurrency(),
                        e.getCreatedAt()))
                .toList();
        return new PostingResult(
                journalEntry.getId(), journalEntry.getReferenceId(), journalEntry.getDescription(),
                journalEntry.getCreatedAt(), entryResults);
    }

    @Transactional(readOnly = true)
    public Page<LedgerEntryResult> getLedgerEntries(UUID accountId, Pageable pageable) {
        if (!accountRepository.existsById(accountId)) {
            throw new AccountNotFoundException("No account found with id " + accountId);
        }
        return ledgerEntryRepository.findByAccountId(accountId, pageable)
                .map(e -> new LedgerEntryResult(
                        e.getId(), e.getAccount().getId(), e.getEntryType(), e.getAmount(), e.getCurrency(),
                        e.getCreatedAt()));
    }
}
