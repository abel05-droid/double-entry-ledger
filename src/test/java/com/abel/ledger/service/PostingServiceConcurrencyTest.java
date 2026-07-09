package com.abel.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.abel.ledger.domain.account.Account;
import com.abel.ledger.domain.account.AccountType;
import com.abel.ledger.dto.AccountBalance;
import com.abel.ledger.dto.LedgerEntryRequest;
import com.abel.ledger.dto.PostingRequest;
import com.abel.ledger.dto.PostingResult;
import com.abel.ledger.repository.AccountRepository;
import com.abel.ledger.repository.LedgerEntryRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Fires real concurrent requests, via a real thread pool, at the posting
 * and balance-reading paths and checks the resulting database state
 * directly — not just the in-process return values — since a race that
 * only shows up as a transient exception or a stale read would otherwise
 * go unnoticed.
 *
 * Runs against the docker-compose Postgres stack, same as
 * {@link PostingServiceIntegrationTest} — {@code docker compose up -d
 * postgres} must be running locally. Each test loops several iterations
 * with fresh accounts/keys per iteration, because a concurrency bug is
 * probabilistic and can pass by luck on a single run.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class PostingServiceConcurrencyTest {

    private static final int ITERATIONS = 5;
    private static final int CONCURRENT_REQUESTS = 32;

    @Autowired
    private PostingService postingService;

    @Autowired
    private BalanceService balanceService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Account saveAccount(AccountType type) {
        return accountRepository.save(Account.builder()
                .accountNumber("ACC-" + UUID.randomUUID())
                .accountName("Concurrency test " + type)
                .accountType(type)
                .currency("USD")
                .build());
    }

    private PostingRequest balancedRequest(
            String idempotencyKey, String referenceId, Account debitAccount, Account creditAccount, String amount) {
        return new PostingRequest(
                idempotencyKey,
                referenceId,
                "concurrency test posting",
                List.of(new LedgerEntryRequest(debitAccount.getId(), new BigDecimal(amount), "USD")),
                List.of(new LedgerEntryRequest(creditAccount.getId(), new BigDecimal(amount), "USD")));
    }

    /**
     * Runs {@code tasks} on a fresh fixed thread pool, releasing every
     * thread at (as close to) the same instant via a shared latch so the
     * requests genuinely overlap rather than trickling in one at a time.
     */
    private <T> List<T> runConcurrently(List<Callable<T>> tasks) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
        try {
            CountDownLatch ready = new CountDownLatch(tasks.size());
            CountDownLatch start = new CountDownLatch(1);
            List<Callable<T>> gatedTasks = tasks.stream()
                    .<Callable<T>>map(task -> () -> {
                        ready.countDown();
                        start.await();
                        return task.call();
                    })
                    .toList();

            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : gatedTasks) {
                futures.add(executor.submit(task));
            }
            ready.await(10, TimeUnit.SECONDS);
            start.countDown();

            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void concurrentPostingsWithDistinctIdempotencyKeysAgainstSameAccountsStayConsistent() throws Exception {
        for (int iteration = 0; iteration < ITERATIONS; iteration++) {
            Account debitAccount = saveAccount(AccountType.ASSET);
            Account creditAccount = saveAccount(AccountType.REVENUE);

            List<Callable<PostingResult>> tasks = IntStream.range(0, CONCURRENT_REQUESTS)
                    .<Callable<PostingResult>>mapToObj(i -> () -> postingService.post(balancedRequest(
                            "idem-" + UUID.randomUUID(), "REF-" + UUID.randomUUID(),
                            debitAccount, creditAccount, "10.00")))
                    .toList();

            List<PostingResult> results = runConcurrently(tasks);

            assertThat(results).hasSize(CONCURRENT_REQUESTS);
            Set<UUID> journalEntryIds =
                    results.stream().map(PostingResult::journalEntryId).collect(Collectors.toSet());
            assertThat(journalEntryIds)
                    .as("every request used a distinct idempotencyKey, so each must produce its own JournalEntry")
                    .hasSize(CONCURRENT_REQUESTS);

            assertLedgerInvariants(Set.of(debitAccount.getId(), creditAccount.getId()));

            BigDecimal expectedMagnitude = new BigDecimal("10.00").multiply(BigDecimal.valueOf(CONCURRENT_REQUESTS));
            assertThat(balanceService.getBalance(debitAccount.getId()).balance())
                    .isEqualByComparingTo(expectedMagnitude);
            assertThat(balanceService.getBalance(creditAccount.getId()).balance())
                    .isEqualByComparingTo(expectedMagnitude);
        }
    }

    @Test
    void concurrentPostingsWithSameIdempotencyKeySimulateRetryStormWithoutDuplicating() throws Exception {
        for (int iteration = 0; iteration < ITERATIONS; iteration++) {
            Account debitAccount = saveAccount(AccountType.ASSET);
            Account creditAccount = saveAccount(AccountType.REVENUE);
            String sharedIdempotencyKey = "idem-" + UUID.randomUUID();
            PostingRequest sharedRequest = balancedRequest(
                    sharedIdempotencyKey, "REF-" + UUID.randomUUID(), debitAccount, creditAccount, "25.00");

            List<Callable<PostingResult>> tasks = IntStream.range(0, CONCURRENT_REQUESTS)
                    .<Callable<PostingResult>>mapToObj(i -> () -> postingService.post(sharedRequest))
                    .toList();

            List<PostingResult> results = runConcurrently(tasks);

            assertThat(results).hasSize(CONCURRENT_REQUESTS);
            Set<UUID> journalEntryIds =
                    results.stream().map(PostingResult::journalEntryId).collect(Collectors.toSet());
            assertThat(journalEntryIds)
                    .as("every concurrent double-click on the same idempotencyKey must converge on one JournalEntry")
                    .hasSize(1);

            UUID journalEntryId = journalEntryIds.iterator().next();
            assertThat(ledgerEntryRepository.findByJournalEntryId(journalEntryId)).hasSize(2);

            Long keyCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM idempotency_keys WHERE idempotency_key = ?",
                    Long.class, sharedIdempotencyKey);
            assertThat(keyCount).isEqualTo(1L);

            assertLedgerInvariants(Set.of(debitAccount.getId(), creditAccount.getId()));

            assertThat(balanceService.getBalance(debitAccount.getId()).balance())
                    .isEqualByComparingTo(new BigDecimal("25.00"));
            assertThat(balanceService.getBalance(creditAccount.getId()).balance())
                    .isEqualByComparingTo(new BigDecimal("25.00"));
        }
    }

    @Test
    void balanceReadsDuringConcurrentPostingNeverObserveATornOrNegativeState() throws Exception {
        for (int iteration = 0; iteration < ITERATIONS; iteration++) {
            Account debitAccount = saveAccount(AccountType.ASSET);
            Account creditAccount = saveAccount(AccountType.REVENUE);
            BigDecimal increment = new BigDecimal("5.00");

            AtomicBoolean postingInFlight = new AtomicBoolean(true);
            List<BigDecimal> observedBalances = new ArrayList<>();
            Object observedBalancesLock = new Object();

            ExecutorService readerExecutor = Executors.newFixedThreadPool(4);
            List<Future<?>> readerFutures = new ArrayList<>();
            for (int r = 0; r < 4; r++) {
                readerFutures.add(readerExecutor.submit(() -> {
                    while (postingInFlight.get()) {
                        AccountBalance balance = balanceService.getBalance(debitAccount.getId());
                        synchronized (observedBalancesLock) {
                            observedBalances.add(balance.balance());
                        }
                    }
                }));
            }

            try {
                List<Callable<PostingResult>> postingTasks = IntStream.range(0, CONCURRENT_REQUESTS)
                        .<Callable<PostingResult>>mapToObj(i -> () -> postingService.post(balancedRequest(
                                "idem-" + UUID.randomUUID(), "REF-" + UUID.randomUUID(),
                                debitAccount, creditAccount, increment.toPlainString())))
                        .toList();

                List<PostingResult> results = runConcurrently(postingTasks);
                assertThat(results).hasSize(CONCURRENT_REQUESTS);
            } finally {
                postingInFlight.set(false);
                readerExecutor.shutdown();
                for (Future<?> f : readerFutures) {
                    f.get(30, TimeUnit.SECONDS);
                }
            }

            BigDecimal finalBalance = balanceService.getBalance(debitAccount.getId()).balance();
            BigDecimal expectedFinal = increment.multiply(BigDecimal.valueOf(CONCURRENT_REQUESTS));
            assertThat(finalBalance).isEqualByComparingTo(expectedFinal);

            synchronized (observedBalancesLock) {
                for (BigDecimal observed : observedBalances) {
                    assertThat(observed.signum())
                            .as("a debit-normal balance must never be negative")
                            .isGreaterThanOrEqualTo(0);
                    assertThat(observed.remainder(increment).signum())
                            .as("every observed balance must be a whole multiple of the posting increment — "
                                    + "a fractional value would mean a read saw one ledger entry of a "
                                    + "journal entry's pair but not the other")
                            .isZero();
                    assertThat(observed).isLessThanOrEqualTo(expectedFinal);
                }
            }

            assertLedgerInvariants(Set.of(debitAccount.getId(), creditAccount.getId()));
        }
    }

    /**
     * Verifies the ledger invariants directly against the database rather
     * than trusting in-process state: every JournalEntry is balanced, every
     * LedgerEntry belongs to exactly one (existing) JournalEntry, no
     * idempotency key is duplicated, and each involved account's derived
     * balance equals the direct sum of its ledger entries.
     */
    private void assertLedgerInvariants(Set<UUID> accountIds) {
        List<UUID> unbalancedJournalEntries = jdbcTemplate.query(
                """
                SELECT je.id
                FROM journal_entries je
                JOIN ledger_entries le ON le.journal_entry_id = je.id
                GROUP BY je.id
                HAVING SUM(CASE WHEN le.entry_type = 'DEBIT' THEN le.amount ELSE 0 END)
                    <> SUM(CASE WHEN le.entry_type = 'CREDIT' THEN le.amount ELSE 0 END)
                """,
                (rs, rowNum) -> (UUID) rs.getObject("id"));
        assertThat(unbalancedJournalEntries).as("every JournalEntry must be balanced").isEmpty();

        Long orphanLedgerEntryCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM ledger_entries le
                LEFT JOIN journal_entries je ON je.id = le.journal_entry_id
                WHERE je.id IS NULL
                """,
                Long.class);
        assertThat(orphanLedgerEntryCount)
                .as("every LedgerEntry must belong to exactly one existing JournalEntry")
                .isZero();

        List<String> duplicateIdempotencyKeys = jdbcTemplate.query(
                "SELECT idempotency_key FROM idempotency_keys GROUP BY idempotency_key HAVING COUNT(*) > 1",
                (rs, rowNum) -> rs.getString("idempotency_key"));
        assertThat(duplicateIdempotencyKeys).as("no idempotency key may be duplicated").isEmpty();

        for (UUID accountId : accountIds) {
            Account account = accountRepository.findById(accountId).orElseThrow();
            BigDecimal debitTotal = jdbcTemplate.queryForObject(
                    "SELECT COALESCE(SUM(amount), 0) FROM ledger_entries WHERE account_id = ? AND entry_type = 'DEBIT'",
                    BigDecimal.class, accountId);
            BigDecimal creditTotal = jdbcTemplate.queryForObject(
                    "SELECT COALESCE(SUM(amount), 0) FROM ledger_entries WHERE account_id = ? AND entry_type = 'CREDIT'",
                    BigDecimal.class, accountId);
            BigDecimal expectedBalance = switch (account.getAccountType()) {
                case ASSET, EXPENSE -> debitTotal.subtract(creditTotal);
                case LIABILITY, EQUITY, REVENUE -> creditTotal.subtract(debitTotal);
            };

            assertThat(balanceService.getBalance(accountId).balance())
                    .as("derived balance for account %s must equal the direct sum of its ledger entries", accountId)
                    .isEqualByComparingTo(expectedBalance);
        }
    }
}
