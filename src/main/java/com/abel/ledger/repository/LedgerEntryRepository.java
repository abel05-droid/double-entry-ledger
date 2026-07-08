package com.abel.ledger.repository;

import com.abel.ledger.domain.ledger.LedgerEntry;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {

    List<LedgerEntry> findByAccountId(UUID accountId);

    Page<LedgerEntry> findByAccountId(UUID accountId, Pageable pageable);

    List<LedgerEntry> findByJournalEntryId(UUID journalEntryId);

    /**
     * Aggregates debit and credit totals for an account directly in
     * PostgreSQL via SUM/CASE. Entries are never loaded into memory for
     * balance derivation.
     */
    @Query(value = """
            SELECT
                COALESCE(SUM(CASE WHEN entry_type = 'DEBIT' THEN amount ELSE 0 END), 0) AS debitTotal,
                COALESCE(SUM(CASE WHEN entry_type = 'CREDIT' THEN amount ELSE 0 END), 0) AS creditTotal
            FROM ledger_entries
            WHERE account_id = :accountId
            """, nativeQuery = true)
    AccountEntryTotals sumEntriesByAccountId(@Param("accountId") UUID accountId);

    interface AccountEntryTotals {
        BigDecimal getDebitTotal();

        BigDecimal getCreditTotal();
    }
}
