package com.abel.ledger.repository;

import com.abel.ledger.domain.ledger.LedgerEntry;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {

    List<LedgerEntry> findByAccountId(UUID accountId);

    List<LedgerEntry> findByJournalEntryId(UUID journalEntryId);
}
