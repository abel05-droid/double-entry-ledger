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
import com.abel.ledger.event.JournalEntryPostedEvent;
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
import java.time.Instant;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

/**
 * Validates and posts complete, balanced double-entry transactions.
 *
 * {@link #postWithinTransaction(PostingRequest)} is the sole
 * {@code @Transactional} write boundary in this class: a journal entry and
 * all of its ledger entries are validated and persisted atomically, or
 * nothing is persisted at all. Posted records are never updated or deleted
 * — corrections happen by posting a new, separate correcting transaction.
 *
 * <p>{@link #post(PostingRequest)} is the public entry point. It wraps that
 * transactional attempt with recovery for exactly one concurrency race: two
 * requests carrying the same {@code idempotencyKey} both pass the
 * check-then-act {@code findByIdempotencyKey} lookup before either commits
 * (unavoidable under {@code READ COMMITTED}, and not worth escalating
 * isolation to close — see {@code docs/architecture.md}). The loser's
 * transaction then fails a unique-constraint check at commit — either on
 * {@code idempotency_keys.idempotency_key} directly, or, if the racing
 * requests also share a {@code referenceId} (the common case: the same
 * logical request retried), on {@code journal_entries.reference_id} first,
 * since that insert happens earlier in the method. Either way the whole
 * losing transaction rolls back atomically, so no duplicate
 * {@code JournalEntry} or orphaned {@code LedgerEntry} rows are ever
 * possible. {@link #recoverFromConcurrentIdempotencyKeyInsert} distinguishes
 * "this failure was that race" from "this is a genuine, unrelated conflict"
 * by checking whether an {@code idempotency_keys} row for this exact
 * request's key now exists — not by matching a specific constraint name —
 * and, if so, replays the winner's result instead of surfacing a 500 to a
 * client that did nothing wrong.
 *
 * <p>{@code postWithinTransaction} also raises a {@link JournalEntryPostedEvent}
 * as its last step, immediately after the {@code IdempotencyKey} is saved —
 * never on the {@code replay()} path, so a sequential idempotent replay or
 * a recovered concurrency-race loser never raises one. This class has no
 * dependency on Kafka or any other messaging technology: it publishes
 * through Spring's {@link ApplicationEventPublisher}, and
 * {@code com.abel.ledger.kafka.LedgerEventPublisher} is solely responsible
 * for turning that into a Kafka message, and only after this method's
 * transaction actually commits. See {@code docs/architecture.md},
 * "Event Publishing".
 */
@Service
public class PostingService {

    private final JournalEntryRepository journalEntryRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final AccountRepository accountRepository;
    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Self-reference used to invoke the {@code @Transactional} methods
     * below through Spring's proxy rather than via a plain {@code this}
     * call, which would silently bypass the proxy (and therefore the
     * transaction) entirely. {@code @Lazy} breaks the resulting circular
     * dependency by injecting a lazily-resolved proxy instead of eagerly
     * instantiating this bean while it's still being constructed.
     */
    private final PostingService self;

    public PostingService(
            JournalEntryRepository journalEntryRepository,
            LedgerEntryRepository ledgerEntryRepository,
            AccountRepository accountRepository,
            IdempotencyKeyRepository idempotencyKeyRepository,
            ApplicationEventPublisher eventPublisher) {
        this(journalEntryRepository, ledgerEntryRepository, accountRepository, idempotencyKeyRepository,
                eventPublisher, null);
    }

    @Autowired
    public PostingService(
            JournalEntryRepository journalEntryRepository,
            LedgerEntryRepository ledgerEntryRepository,
            AccountRepository accountRepository,
            IdempotencyKeyRepository idempotencyKeyRepository,
            ApplicationEventPublisher eventPublisher,
            @Lazy PostingService self) {
        this.journalEntryRepository = journalEntryRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.accountRepository = accountRepository;
        this.idempotencyKeyRepository = idempotencyKeyRepository;
        this.eventPublisher = eventPublisher;
        this.self = self != null ? self : this;
    }

    public PostingResult post(PostingRequest request) {
        validateStructure(request);
        try {
            return self.postWithinTransaction(request);
        } catch (DataIntegrityViolationException ex) {
            return self.recoverFromConcurrentIdempotencyKeyInsert(request, ex);
        }
    }

    @Transactional
    public PostingResult postWithinTransaction(PostingRequest request) {
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

        // journalEntry.getCreatedAt() is deliberately not used for postedAt:
        // it's populated by @CreationTimestamp only on flush, which hasn't
        // happened yet at this point in the method (see the Phase 3 "known
        // tradeoff" note in docs/architecture.md) — Instant.now() here is
        // both correct (this is genuinely when the posting happened) and
        // avoids that timing gotcha entirely.
        eventPublisher.publishEvent(new JournalEntryPostedEvent(
                journalEntry.getId(), journalEntry.getReferenceId(), Instant.now(), Set.copyOf(accountsById.keySet())));

        return toResult(journalEntry, savedEntries);
    }

    /**
     * Recovers from a losing concurrency race on this request's
     * idempotencyKey: by the time this runs, {@code postWithinTransaction}'s
     * whole attempt (journal entry, ledger entries, and the idempotency key
     * insert) has already been rolled back atomically, so the only trace of
     * this attempt is the exception itself. This is deliberately
     * distinguished from a genuine, unrelated conflict (e.g. a
     * {@code reference_id} collision between two requests with different
     * idempotencyKeys, as in {@code PostingServiceIntegrationTest
     * .duplicateReferenceIdViolatesDatabaseConstraintAndRollsBackCompletely})
     * not by matching a specific constraint name, but by checking whether an
     * {@code idempotency_keys} row now exists for THIS request's key: if the
     * race was on the shared {@code referenceId} of two requests carrying the
     * same idempotencyKey, that row belongs to the winner, who reached and
     * committed the idempotency key insert before this transaction's earlier
     * {@code reference_id} insert could even fail. If no such row exists,
     * this failure had nothing to do with a race on this key — rethrow it
     * unchanged.
     */
    @Transactional(readOnly = true)
    public PostingResult recoverFromConcurrentIdempotencyKeyInsert(
            PostingRequest request, DataIntegrityViolationException raceCause) {
        String fingerprint = fingerprint(request);
        IdempotencyKey winningKey = idempotencyKeyRepository.findByIdempotencyKey(request.idempotencyKey())
                .orElseThrow(() -> raceCause);
        return replay(winningKey, fingerprint);
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
