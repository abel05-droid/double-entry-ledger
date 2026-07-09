package com.abel.ledger.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.abel.ledger.domain.account.Account;
import com.abel.ledger.domain.account.AccountType;
import com.abel.ledger.exception.AccountNotFoundException;
import com.abel.ledger.repository.AccountRepository;
import com.abel.ledger.service.BalanceService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Proves {@code ledger.balance.requests} / {@code ledger.balance.duration}
 * (added by {@link BalanceObservabilityAspect}) record correctly for both
 * outcomes, without any change to {@code BalanceService} itself.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class BalanceObservabilityIntegrationTest {

    @Autowired
    private BalanceService balanceService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    void successfulBalanceLookupIncrementsSuccessMetrics() {
        Account account = accountRepository.save(Account.builder()
                .accountNumber("ACC-" + UUID.randomUUID())
                .accountName("Balance observability test")
                .accountType(AccountType.ASSET)
                .currency("USD")
                .build());

        var counter = meterRegistry.counter("ledger.balance.requests", "outcome", "success");
        Timer timer = meterRegistry.timer("ledger.balance.duration", "outcome", "success");
        double before = counter.count();
        long durationBefore = timer.count();

        balanceService.getBalance(account.getId());

        assertThat(counter.count()).isEqualTo(before + 1);
        assertThat(timer.count()).isEqualTo(durationBefore + 1);
    }

    @Test
    void failedBalanceLookupIncrementsFailureMetrics() {
        UUID unknownAccountId = UUID.randomUUID();

        var counter = meterRegistry.counter("ledger.balance.requests", "outcome", "failure");
        double before = counter.count();

        assertThatThrownBy(() -> balanceService.getBalance(unknownAccountId))
                .isInstanceOf(AccountNotFoundException.class);

        assertThat(counter.count()).isEqualTo(before + 1);
    }
}
