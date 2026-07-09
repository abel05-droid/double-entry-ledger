package com.abel.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.abel.ledger.domain.account.Account;
import com.abel.ledger.domain.account.AccountType;
import com.abel.ledger.dto.LedgerEntryRequest;
import com.abel.ledger.dto.PostingRequest;
import com.abel.ledger.dto.PostingResult;
import com.abel.ledger.repository.AccountRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Repeatable stress test simulating heavy concurrent posting load against a
 * single pair of accounts. Deliberately named so it does NOT match
 * maven-surefire-plugin's default test-discovery patterns
 * ({@code **&#47;*Test.java}, {@code **&#47;*Tests.java},
 * {@code **&#47;*TestCase.java}), so {@code mvn test} never picks it up on
 * its own — it is slow and meant for manual/exploratory load verification,
 * not fast feedback in CI.
 *
 * <p>To run it: {@code docker compose up -d postgres} must be running
 * locally, then:
 *
 * <pre>{@code
 * ./mvnw test -Dtest=PostingServiceStressCheck
 * }</pre>
 *
 * <p>Tune {@code REQUEST_COUNT} and {@code CONCURRENCY} below to scale the
 * load; both are deliberately much higher than {@link PostingServiceConcurrencyTest}'s.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class PostingServiceStressCheck {

    private static final Logger log = LoggerFactory.getLogger(PostingServiceStressCheck.class);

    private static final int REQUEST_COUNT = 2000;
    private static final int CONCURRENCY = 50;

    @Autowired
    private PostingService postingService;

    @Autowired
    private BalanceService balanceService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void handlesThousandsOfConcurrentPostingsAgainstTheSameAccountPair() throws Exception {
        Account debitAccount = accountRepository.save(Account.builder()
                .accountNumber("STRESS-DR-" + UUID.randomUUID())
                .accountName("Stress test debit account")
                .accountType(AccountType.ASSET)
                .currency("USD")
                .build());
        Account creditAccount = accountRepository.save(Account.builder()
                .accountNumber("STRESS-CR-" + UUID.randomUUID())
                .accountName("Stress test credit account")
                .accountType(AccountType.REVENUE)
                .currency("USD")
                .build());
        BigDecimal increment = new BigDecimal("1.00");

        List<Callable<PostingResult>> tasks = IntStream.range(0, REQUEST_COUNT)
                .<Callable<PostingResult>>mapToObj(i -> () -> postingService.post(new PostingRequest(
                        "stress-idem-" + UUID.randomUUID(),
                        "STRESS-REF-" + UUID.randomUUID(),
                        "stress test posting " + i,
                        List.of(new LedgerEntryRequest(debitAccount.getId(), increment, "USD")),
                        List.of(new LedgerEntryRequest(creditAccount.getId(), increment, "USD")))))
                .toList();

        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENCY);
        long startNanos = System.nanoTime();
        List<Future<PostingResult>> futures;
        try {
            futures = tasks.stream().map(executor::submit).toList();
        } finally {
            executor.shutdown();
        }

        int succeeded = 0;
        int failed = 0;
        for (Future<PostingResult> future : futures) {
            try {
                future.get(2, TimeUnit.MINUTES);
                succeeded++;
            } catch (Exception ex) {
                failed++;
                log.error("stress posting request failed", ex);
            }
        }
        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;

        log.info(
                "Stress test: {} requests, concurrency {}, {} succeeded, {} failed, {} ms elapsed ({} req/s)",
                REQUEST_COUNT, CONCURRENCY, succeeded, failed, elapsedMillis,
                elapsedMillis == 0 ? "n/a" : (REQUEST_COUNT * 1000L / elapsedMillis));

        assertThat(failed).as("every distinct-idempotencyKey stress request should succeed").isZero();
        assertThat(succeeded).isEqualTo(REQUEST_COUNT);

        BigDecimal expectedBalance = increment.multiply(BigDecimal.valueOf(REQUEST_COUNT));
        assertThat(balanceService.getBalance(debitAccount.getId()).balance()).isEqualByComparingTo(expectedBalance);
        assertThat(balanceService.getBalance(creditAccount.getId()).balance()).isEqualByComparingTo(expectedBalance);

        assertLedgerInvariants(Set.of(debitAccount.getId(), creditAccount.getId()));
    }

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
