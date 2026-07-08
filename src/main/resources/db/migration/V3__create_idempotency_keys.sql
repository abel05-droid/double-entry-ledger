-- idempotency_key is deliberately a separate concept from
-- journal_entries.reference_id: reference_id is a business reference for
-- the transaction itself (e.g. an invoice or transfer number), chosen by
-- the caller to identify what the transaction *is*. idempotency_key exists
-- purely as a retry-safety mechanism so that a client safely retrying a
-- network call does not create a duplicate transaction. The two may
-- coincide by convention, but nothing in this schema assumes they do, and
-- conflating them would make it impossible to reuse a business reference
-- across a legitimate correction/reversal flow in later phases.
CREATE TABLE idempotency_keys (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    idempotency_key      VARCHAR(255) NOT NULL,
    request_fingerprint  VARCHAR(64)  NOT NULL,
    journal_entry_id     UUID NOT NULL,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_idempotency_keys_key UNIQUE (idempotency_key),
    CONSTRAINT fk_idempotency_keys_journal_entry
        FOREIGN KEY (journal_entry_id) REFERENCES journal_entries (id)
);

CREATE INDEX idx_idempotency_keys_journal_entry_id ON idempotency_keys (journal_entry_id);
