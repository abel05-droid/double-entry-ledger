package com.abel.ledger.service;

import com.abel.ledger.domain.account.Account;
import com.abel.ledger.dto.AccountBalance;
import com.abel.ledger.exception.AccountNotFoundException;
import com.abel.ledger.repository.AccountRepository;
import com.abel.ledger.repository.LedgerEntryRepository;
import com.abel.ledger.repository.LedgerEntryRepository.AccountEntryTotals;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Derives account balances on demand by aggregating {@code ledger_entries}
 * in the database. No balance is ever cached or stored anywhere — every
 * call performs a live SUM against PostgreSQL.
 *
 * Normal balance convention applied here: ASSET and EXPENSE accounts are
 * debit-normal (a debit increases the balance, a credit decreases it);
 * LIABILITY, EQUITY, and REVENUE accounts are credit-normal (a credit
 * increases the balance, a debit decreases it). This is standard
 * double-entry bookkeeping and must stay consistent with how entries are
 * posted by {@link PostingService}.
 */
@Service
public class BalanceService {

    private final AccountRepository accountRepository;
    private final LedgerEntryRepository ledgerEntryRepository;

    public BalanceService(AccountRepository accountRepository, LedgerEntryRepository ledgerEntryRepository) {
        this.accountRepository = accountRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
    }

    @Transactional(readOnly = true)
    public AccountBalance getBalance(UUID accountId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException("No account found with id " + accountId));

        AccountEntryTotals totals = ledgerEntryRepository.sumEntriesByAccountId(accountId);
        BigDecimal debitTotal = totals.getDebitTotal();
        BigDecimal creditTotal = totals.getCreditTotal();

        BigDecimal balance = switch (account.getAccountType()) {
            case ASSET, EXPENSE -> debitTotal.subtract(creditTotal);
            case LIABILITY, EQUITY, REVENUE -> creditTotal.subtract(debitTotal);
        };

        return new AccountBalance(account.getId(), account.getCurrency(), balance);
    }
}
