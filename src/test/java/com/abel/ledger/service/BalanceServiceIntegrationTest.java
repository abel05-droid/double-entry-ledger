package com.abel.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.abel.ledger.domain.account.Account;
import com.abel.ledger.domain.account.AccountType;
import com.abel.ledger.dto.AccountBalance;
import com.abel.ledger.dto.LedgerEntryRequest;
import com.abel.ledger.dto.PostingRequest;
import com.abel.ledger.repository.AccountRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

// Runs against the docker-compose Postgres stack (see LedgerApplicationTests
// for why Testcontainers is not used in this environment); `docker compose
// up -d postgres` must be running locally for this class to pass. This
// class exists specifically to exercise the native SUM/CASE aggregation
// query against real PostgreSQL, which a mocked repository cannot verify.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class BalanceServiceIntegrationTest {

    @Autowired
    private PostingService postingService;

    @Autowired
    private BalanceService balanceService;

    @Autowired
    private AccountRepository accountRepository;

    private Account saveAccount(AccountType type) {
        return accountRepository.save(Account.builder()
                .accountNumber("ACC-" + UUID.randomUUID())
                .accountName("Test " + type)
                .accountType(type)
                .currency("USD")
                .build());
    }

    @Test
    void derivesAssetAndRevenueBalancesFromPostedEntries() {
        Account cash = saveAccount(AccountType.ASSET);
        Account revenue = saveAccount(AccountType.REVENUE);

        postingService.post(new PostingRequest(
                "idem-" + UUID.randomUUID(),
                "REF-" + UUID.randomUUID(),
                "sale",
                List.of(new LedgerEntryRequest(cash.getId(), new BigDecimal("300.00"), "USD")),
                List.of(new LedgerEntryRequest(revenue.getId(), new BigDecimal("300.00"), "USD"))));

        postingService.post(new PostingRequest(
                "idem-" + UUID.randomUUID(),
                "REF-" + UUID.randomUUID(),
                "refund",
                List.of(new LedgerEntryRequest(revenue.getId(), new BigDecimal("50.00"), "USD")),
                List.of(new LedgerEntryRequest(cash.getId(), new BigDecimal("50.00"), "USD"))));

        AccountBalance cashBalance = balanceService.getBalance(cash.getId());
        AccountBalance revenueBalance = balanceService.getBalance(revenue.getId());

        assertThat(cashBalance.balance()).isEqualByComparingTo("250.00");
        assertThat(revenueBalance.balance()).isEqualByComparingTo("250.00");
    }
}
