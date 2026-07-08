package com.abel.ledger.domain.idempotency;

import com.abel.ledger.domain.journal.JournalEntry;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.hibernate.annotations.CreationTimestamp;

/**
 * Records that a client-supplied idempotency key has already been used to
 * post a {@link JournalEntry}, together with a fingerprint of the request
 * payload that produced it.
 *
 * This is deliberately a separate concept from {@code JournalEntry#getReferenceId()}:
 * referenceId is a business reference chosen by the caller to identify the
 * transaction itself, while idempotencyKey exists purely so that a client
 * safely retrying a network call does not create a duplicate transaction.
 * See the V3 migration for the full rationale.
 */
@Entity
@Table(name = "idempotency_keys")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@EqualsAndHashCode(of = "id")
@ToString
public class IdempotencyKey {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @NotBlank
    @Size(max = 255)
    @Column(name = "idempotency_key", nullable = false, unique = true, updatable = false, length = 255)
    private String idempotencyKey;

    @NotBlank
    @Size(min = 64, max = 64)
    @Column(name = "request_fingerprint", nullable = false, updatable = false, length = 64)
    private String requestFingerprint;

    @NotNull
    @Column(name = "journal_entry_id", nullable = false, updatable = false)
    private UUID journalEntryId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
