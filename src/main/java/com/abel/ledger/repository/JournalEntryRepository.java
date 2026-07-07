package com.abel.ledger.repository;

import com.abel.ledger.domain.journal.JournalEntry;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JournalEntryRepository extends JpaRepository<JournalEntry, UUID> {

    Optional<JournalEntry> findByReferenceId(String referenceId);

    boolean existsByReferenceId(String referenceId);
}
