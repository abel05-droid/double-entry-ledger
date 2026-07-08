package com.abel.ledger.service;

import com.abel.ledger.domain.account.Account;
import com.abel.ledger.domain.idempotency.IdempotencyKey;
import com.abel.ledger.domain.journal.JournalEntry;
import com.abel.ledger.domain.ledger.EntryType;
import com.abel.ledger.domain.ledger.LedgerEntry;
import com.abel.ledger.dto.LedgerEntryRequest;
import com.abel.ledger.dto.LedgerEntryResult;
import com.abel.ledger.dto.PostingRequest;
import com.abel.ledger.dto.PostingResult;
import com.abel.ledger.exception.AccountNotFoundException;
import com.abel.ledger.exception.CurrencyMismatchException;
import com.abel.ledger.exception.IdempotencyKeyConflictException;
import com.abel.ledger.exception.InvalidPostingRequestException;
import com.abel.ledger.exception.UnbalancedJournalEntryException;
import com.abel.ledger.repository.AccountRepository;
import com.abel.ledger.repository.IdempotencyKeyRepository;
import com.abel.ledger.repository.JournalEntryRepository;
import com.abel.ledger.repository.LedgerEntryRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

/**
 * Validates and posts complete, balanced double-entry transactions.
 *
 * {@link #post(PostingRequest)} is the sole entry point into the posting
 * engine and the sole {@code @Transactional} boundary in this class: a
 * journal entry and all of its ledger entries are validated and persisted
 * atomically, or nothing is persisted at all. Posted records are never
 * updated or deleted — corrections happen by posting a new, separate
 * correcting transaction.
 */
@Service
public class PostingService {

    private final JournalEntryRepository journalEntryRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final AccountRepository accountRepository;
    private final IdempotencyKeyRepository idempotencyKeyRepository;

    public PostingService(
            JournalEntryRepository journalEntryRepository,
            LedgerEntryRepository ledgerEntryRepository,
            AccountRepository accountRepository,
            IdempotencyKeyRepository idempotencyKeyRepository) {
        this.journalEntryRepository = journalEntryRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.accountRepository = accountRepository;
        this.idempotencyKeyRepository = idempotencyKeyRepository;
    }

    @Transactional
    public PostingResult post(PostingRequest request) {
        validateStructure(request);

        String fingerprint = fingerprint(request);
        Optional<IdempotencyKey> existingKey = idempotencyKeyRepository.findByIdempotencyKey(request.idempotencyKey());
        if (existingKey.isPresent()) {
            return replay(existingKey.get(), fingerprint);
        }

        List<LedgerEntryRequest> allEntries = new ArrayList<>(
                request.debitEntries().size() + request.creditEntries().size());
        allEntries.addAll(request.debitEntries());
        allEntries.addAll(request.creditEntries());

        Map<UUID, Account> accountsById = loadAccounts(allEntries);
        validateEntryCurrencies(allEntries, accountsById);
        validateBalanced(request.debitEntries(), request.creditEntries());

        JournalEntry journalEntry = journalEntryRepository.save(
                JournalEntry.builder()
                        .referenceId(request.referenceId())
                        .description(request.description())
                        .build());

        List<LedgerEntry> toPersist = new ArrayList<>(allEntries.size());
        toPersist.addAll(buildLedgerEntries(request.debitEntries(), EntryType.DEBIT, journalEntry, accountsById));
        toPersist.addAll(buildLedgerEntries(request.creditEntries(), EntryType.CREDIT, journalEntry, accountsById));
        List<LedgerEntry> savedEntries = ledgerEntryRepository.saveAll(toPersist);

        idempotencyKeyRepository.save(
                IdempotencyKey.builder()
                        .idempotencyKey(request.idempotencyKey())
                        .requestFingerprint(fingerprint)
                        .journalEntryId(journalEntry.getId())
                        .build());

        return toResult(journalEntry, savedEntries);
    }

    private PostingResult replay(IdempotencyKey existingKey, String fingerprint) {
        if (!existingKey.getRequestFingerprint().equals(fingerprint)) {
            throw new IdempotencyKeyConflictException(
                    "Idempotency key '" + existingKey.getIdempotencyKey()
                            + "' was already used with a different request payload");
        }
        JournalEntry journalEntry = journalEntryRepository.findById(existingKey.getJournalEntryId())
                .orElseThrow(() -> new IllegalStateException(
                        "Idempotency key '" + existingKey.getIdempotencyKey()
                                + "' references missing journal entry " + existingKey.getJournalEntryId()));
        List<LedgerEntry> entries = ledgerEntryRepository.findByJournalEntryId(journalEntry.getId());
        return toResult(journalEntry, entries);
    }

    private void validateStructure(PostingRequest request) {
        if (request == null) {
            throw new InvalidPostingRequestException("Posting request must not be null");
        }
        if (!StringUtils.hasText(request.idempotencyKey())) {
            throw new InvalidPostingRequestException("idempotencyKey is required");
        }
        if (!StringUtils.hasText(request.referenceId())) {
            throw new InvalidPostingRequestException("referenceId is required");
        }
        if (CollectionUtils.isEmpty(request.debitEntries())) {
            throw new InvalidPostingRequestException("At least one debit entry is required");
        }
        if (CollectionUtils.isEmpty(request.creditEntries())) {
            throw new InvalidPostingRequestException("At least one credit entry is required");
        }
        Stream.concat(request.debitEntries().stream(), request.creditEntries().stream())
                .forEach(this::validateEntryStructure);
    }

    private void validateEntryStructure(LedgerEntryRequest entry) {
        if (entry == null) {
            throw new InvalidPostingRequestException("Ledger entry must not be null");
        }
        if (entry.accountId() == null) {
            throw new InvalidPostingRequestException("Ledger entry accountId is required");
        }
        if (entry.amount() == null || entry.amount().signum() <= 0) {
            throw new InvalidPostingRequestException("Ledger entry amount must be greater than zero");
        }
        if (entry.amount().scale() > 4) {
            throw new InvalidPostingRequestException("Ledger entry amount must not exceed 4 decimal places");
        }
        if (!StringUtils.hasText(entry.currency()) || entry.currency().length() != 3) {
            throw new InvalidPostingRequestException("Ledger entry currency must be a 3-letter ISO 4217 code");
        }
    }

    private Map<UUID, Account> loadAccounts(List<LedgerEntryRequest> entries) {
        Set<UUID> accountIds = entries.stream().map(LedgerEntryRequest::accountId).collect(Collectors.toSet());
        Map<UUID, Account> accountsById = accountRepository.findAllById(accountIds).stream()
                .collect(Collectors.toMap(Account::getId, Function.identity()));
        for (UUID accountId : accountIds) {
            if (!accountsById.containsKey(accountId)) {
                throw new AccountNotFoundException("No account found with id " + accountId);
            }
        }
        return accountsById;
    }

    private void validateEntryCurrencies(List<LedgerEntryRequest> entries, Map<UUID, Account> accountsById) {
        for (LedgerEntryRequest entry : entries) {
            Account account = accountsById.get(entry.accountId());
            if (!entry.currency().equals(account.getCurrency())) {
                throw new CurrencyMismatchException(
                        "Ledger entry currency '" + entry.currency() + "' does not match account '"
                                + account.getAccountNumber() + "' currency '" + account.getCurrency() + "'");
            }
        }
        long distinctCurrencies = entries.stream().map(LedgerEntryRequest::currency).distinct().count();
        if (distinctCurrencies > 1) {
            throw new CurrencyMismatchException(
                    "All ledger entries in a single journal entry must use the same currency; "
                            + "multi-currency postings are not supported in this phase");
        }
    }

    private void validateBalanced(List<LedgerEntryRequest> debitEntries, List<LedgerEntryRequest> creditEntries) {
        BigDecimal debitTotal = sum(debitEntries);
        BigDecimal creditTotal = sum(creditEntries);
        if (debitTotal.compareTo(creditTotal) != 0) {
            throw new UnbalancedJournalEntryException(
                    "Total debits (" + debitTotal + ") must equal total credits (" + creditTotal + ")");
        }
    }

    private BigDecimal sum(List<LedgerEntryRequest> entries) {
        return entries.stream().map(LedgerEntryRequest::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private List<LedgerEntry> buildLedgerEntries(
            List<LedgerEntryRequest> entries, EntryType entryType, JournalEntry journalEntry,
            Map<UUID, Account> accountsById) {
        return entries.stream()
                .map(entry -> LedgerEntry.builder()
                        .journalEntry(journalEntry)
                        .account(accountsById.get(entry.accountId()))
                        .entryType(entryType)
                        .amount(entry.amount())
                        .currency(entry.currency())
                        .build())
                .toList();
    }

    private PostingResult toResult(JournalEntry journalEntry, List<LedgerEntry> entries) {
        List<LedgerEntryResult> entryResults = entries.stream()
                .map(e -> new LedgerEntryResult(
                        e.getId(), e.getAccount().getId(), e.getEntryType(), e.getAmount(), e.getCurrency(),
                        e.getCreatedAt()))
                .toList();
        return new PostingResult(
                journalEntry.getId(), journalEntry.getReferenceId(), journalEntry.getDescription(),
                journalEntry.getCreatedAt(), entryResults);
    }

    /**
     * Fingerprints everything about a posting request except the
     * idempotencyKey itself, so a replayed request can be recognized as
     * identical (or flagged as conflicting) regardless of key.
     */
    private String fingerprint(PostingRequest request) {
        StringBuilder canonical = new StringBuilder();
        canonical.append(request.referenceId()).append(' ');
        canonical.append(request.description() == null ? "" : request.description()).append(' ');
        appendEntries(canonical, request.debitEntries());
        canonical.append(' ');
        appendEntries(canonical, request.creditEntries());
        return sha256Hex(canonical.toString());
    }

    private void appendEntries(StringBuilder canonical, List<LedgerEntryRequest> entries) {
        for (LedgerEntryRequest entry : entries) {
            canonical.append(entry.accountId()).append(':')
                    .append(entry.amount().stripTrailingZeros().toPlainString()).append(':')
                    .append(entry.currency()).append(';');
        }
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
