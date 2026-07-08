package com.abel.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.abel.ledger.domain.account.Account;
import com.abel.ledger.domain.account.AccountType;
import com.abel.ledger.dto.LedgerEntryRequest;
import com.abel.ledger.dto.PostingRequest;
import com.abel.ledger.dto.PostingResult;
import com.abel.ledger.exception.IdempotencyKeyConflictException;
import com.abel.ledger.exception.UnbalancedJournalEntryException;
import com.abel.ledger.repository.AccountRepository;
import com.abel.ledger.repository.IdempotencyKeyRepository;
import com.abel.ledger.repository.JournalEntryRepository;
import com.abel.ledger.repository.LedgerEntryRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;

// Runs against the docker-compose Postgres stack (see LedgerApplicationTests
// for why Testcontainers is not used in this environment); `docker compose
// up -d postgres` must be running locally for this class to pass.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class PostingServiceIntegrationTest {

    @Autowired
    private PostingService postingService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private JournalEntryRepository journalEntryRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private IdempotencyKeyRepository idempotencyKeyRepository;

    private Account saveAccount(AccountType type) {
        return accountRepository.save(Account.builder()
                .accountNumber("ACC-" + UUID.randomUUID())
                .accountName("Test " + type)
                .accountType(type)
                .currency("USD")
                .build());
    }

    private PostingRequest balancedRequest(
            String idempotencyKey, String referenceId, Account debitAccount, Account creditAccount, String amount) {
        return new PostingRequest(
                idempotencyKey,
                referenceId,
                "integration test posting",
                List.of(new LedgerEntryRequest(debitAccount.getId(), new BigDecimal(amount), "USD")),
                List.of(new LedgerEntryRequest(creditAccount.getId(), new BigDecimal(amount), "USD")));
    }

    @Test
    void samePayloadUnderSameIdempotencyKeyReturnsOriginalResultWithoutDuplicating() {
        Account debitAccount = saveAccount(AccountType.ASSET);
        Account creditAccount = saveAccount(AccountType.REVENUE);
        String idempotencyKey = "idem-" + UUID.randomUUID();
        PostingRequest request = balancedRequest(
                idempotencyKey, "REF-" + UUID.randomUUID(), debitAccount, creditAccount, "100.00");

        PostingResult first = postingService.post(request);
        PostingResult second = postingService.post(request);

        assertThat(second.journalEntryId()).isEqualTo(first.journalEntryId());
        assertThat(journalEntryRepository.findById(first.journalEntryId())).isPresent();
        assertThat(ledgerEntryRepository.findByJournalEntryId(first.journalEntryId())).hasSize(2);
        assertThat(idempotencyKeyRepository.findByIdempotencyKey(idempotencyKey)).isPresent();
    }

    @Test
    void sameIdempotencyKeyWithDifferentPayloadIsRejected() {
        Account debitAccount = saveAccount(AccountType.ASSET);
        Account creditAccount = saveAccount(AccountType.REVENUE);
        String idempotencyKey = "idem-" + UUID.randomUUID();

        PostingRequest original = balancedRequest(
                idempotencyKey, "REF-" + UUID.randomUUID(), debitAccount, creditAccount, "100.00");
        postingService.post(original);

        PostingRequest conflicting = balancedRequest(
                idempotencyKey, "REF-" + UUID.randomUUID(), debitAccount, creditAccount, "250.00");

        long journalCountBefore = journalEntryRepository.count();

        assertThatThrownBy(() -> postingService.post(conflicting))
                .isInstanceOf(IdempotencyKeyConflictException.class);

        assertThat(journalEntryRepository.count()).isEqualTo(journalCountBefore);
    }

    @Test
    void unbalancedPostingLeavesNoPartialRecords() {
        Account debitAccount = saveAccount(AccountType.ASSET);
        Account creditAccount = saveAccount(AccountType.REVENUE);

        PostingRequest request = new PostingRequest(
                "idem-" + UUID.randomUUID(),
                "REF-" + UUID.randomUUID(),
                "unbalanced",
                List.of(new LedgerEntryRequest(debitAccount.getId(), new BigDecimal("100.00"), "USD")),
                List.of(new LedgerEntryRequest(creditAccount.getId(), new BigDecimal("90.00"), "USD")));

        long journalCountBefore = journalEntryRepository.count();
        long ledgerCountBefore = ledgerEntryRepository.count();
        long idempotencyCountBefore = idempotencyKeyRepository.count();

        assertThatThrownBy(() -> postingService.post(request))
                .isInstanceOf(UnbalancedJournalEntryException.class);

        assertThat(journalEntryRepository.count()).isEqualTo(journalCountBefore);
        assertThat(ledgerEntryRepository.count()).isEqualTo(ledgerCountBefore);
        assertThat(idempotencyKeyRepository.count()).isEqualTo(idempotencyCountBefore);
    }

    @Test
    void duplicateReferenceIdViolatesDatabaseConstraintAndRollsBackCompletely() {
        Account debitAccount = saveAccount(AccountType.ASSET);
        Account creditAccount = saveAccount(AccountType.REVENUE);
        String sharedReferenceId = "REF-" + UUID.randomUUID();

        postingService.post(balancedRequest(
                "idem-" + UUID.randomUUID(), sharedReferenceId, debitAccount, creditAccount, "100.00"));

        long journalCountBefore = journalEntryRepository.count();
        long ledgerCountBefore = ledgerEntryRepository.count();

        PostingRequest duplicateReference = balancedRequest(
                "idem-" + UUID.randomUUID(), sharedReferenceId, debitAccount, creditAccount, "50.00");

        // No app-level pre-check for reference_id uniqueness exists (see
        // PostingService) — this deliberately exercises the real
        // uq_journal_entries_reference_id constraint from the V2 migration
        // to prove the whole transaction, not just the failing insert, rolls back.
        assertThatThrownBy(() -> postingService.post(duplicateReference))
                .isInstanceOf(DataAccessException.class);

        assertThat(journalEntryRepository.count()).isEqualTo(journalCountBefore);
        assertThat(ledgerEntryRepository.count()).isEqualTo(ledgerCountBefore);
    }
}
