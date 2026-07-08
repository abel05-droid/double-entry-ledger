package com.abel.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.abel.ledger.domain.account.Account;
import com.abel.ledger.domain.account.AccountType;
import com.abel.ledger.dto.AccountBalance;
import com.abel.ledger.exception.AccountNotFoundException;
import com.abel.ledger.repository.AccountRepository;
import com.abel.ledger.repository.LedgerEntryRepository;
import com.abel.ledger.repository.LedgerEntryRepository.AccountEntryTotals;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BalanceServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private LedgerEntryRepository ledgerEntryRepository;

    @Mock
    private AccountEntryTotals totals;

    private BalanceService balanceService;

    private static Account account(AccountType type) {
        return Account.builder()
                .id(UUID.randomUUID())
                .accountNumber("ACC-" + type)
                .accountName(type.name())
                .accountType(type)
                .currency("USD")
                .build();
    }

    @Test
    void assetAccountBalanceIsDebitMinusCredit() {
        balanceService = new BalanceService(accountRepository, ledgerEntryRepository);
        Account asset = account(AccountType.ASSET);

        when(accountRepository.findById(asset.getId())).thenReturn(Optional.of(asset));
        when(totals.getDebitTotal()).thenReturn(new BigDecimal("150.00"));
        when(totals.getCreditTotal()).thenReturn(new BigDecimal("50.00"));
        when(ledgerEntryRepository.sumEntriesByAccountId(asset.getId())).thenReturn(totals);

        AccountBalance balance = balanceService.getBalance(asset.getId());

        assertThat(balance.balance()).isEqualByComparingTo("100.00");
    }

    @Test
    void expenseAccountBalanceIsDebitMinusCredit() {
        balanceService = new BalanceService(accountRepository, ledgerEntryRepository);
        Account expense = account(AccountType.EXPENSE);

        when(accountRepository.findById(expense.getId())).thenReturn(Optional.of(expense));
        when(totals.getDebitTotal()).thenReturn(new BigDecimal("80.00"));
        when(totals.getCreditTotal()).thenReturn(new BigDecimal("20.00"));
        when(ledgerEntryRepository.sumEntriesByAccountId(expense.getId())).thenReturn(totals);

        AccountBalance balance = balanceService.getBalance(expense.getId());

        assertThat(balance.balance()).isEqualByComparingTo("60.00");
    }

    @Test
    void liabilityAccountBalanceIsCreditMinusDebit() {
        balanceService = new BalanceService(accountRepository, ledgerEntryRepository);
        Account liability = account(AccountType.LIABILITY);

        when(accountRepository.findById(liability.getId())).thenReturn(Optional.of(liability));
        when(totals.getDebitTotal()).thenReturn(new BigDecimal("30.00"));
        when(totals.getCreditTotal()).thenReturn(new BigDecimal("200.00"));
        when(ledgerEntryRepository.sumEntriesByAccountId(liability.getId())).thenReturn(totals);

        AccountBalance balance = balanceService.getBalance(liability.getId());

        assertThat(balance.balance()).isEqualByComparingTo("170.00");
    }

    @Test
    void equityAccountBalanceIsCreditMinusDebit() {
        balanceService = new BalanceService(accountRepository, ledgerEntryRepository);
        Account equity = account(AccountType.EQUITY);

        when(accountRepository.findById(equity.getId())).thenReturn(Optional.of(equity));
        when(totals.getDebitTotal()).thenReturn(BigDecimal.ZERO);
        when(totals.getCreditTotal()).thenReturn(new BigDecimal("500.00"));
        when(ledgerEntryRepository.sumEntriesByAccountId(equity.getId())).thenReturn(totals);

        AccountBalance balance = balanceService.getBalance(equity.getId());

        assertThat(balance.balance()).isEqualByComparingTo("500.00");
    }

    @Test
    void revenueAccountBalanceIsCreditMinusDebit() {
        balanceService = new BalanceService(accountRepository, ledgerEntryRepository);
        Account revenue = account(AccountType.REVENUE);

        when(accountRepository.findById(revenue.getId())).thenReturn(Optional.of(revenue));
        when(totals.getDebitTotal()).thenReturn(new BigDecimal("10.00"));
        when(totals.getCreditTotal()).thenReturn(new BigDecimal("310.00"));
        when(ledgerEntryRepository.sumEntriesByAccountId(revenue.getId())).thenReturn(totals);

        AccountBalance balance = balanceService.getBalance(revenue.getId());

        assertThat(balance.balance()).isEqualByComparingTo("300.00");
    }

    @Test
    void throwsWhenAccountDoesNotExist() {
        balanceService = new BalanceService(accountRepository, ledgerEntryRepository);
        UUID missingId = UUID.randomUUID();
        when(accountRepository.findById(missingId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> balanceService.getBalance(missingId))
                .isInstanceOf(AccountNotFoundException.class);
    }
}
