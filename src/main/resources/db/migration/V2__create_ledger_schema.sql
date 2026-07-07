CREATE TABLE accounts (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_number  VARCHAR(64)  NOT NULL,
    account_name    VARCHAR(255) NOT NULL,
    account_type    VARCHAR(32)  NOT NULL,
    currency        VARCHAR(3)   NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_accounts_account_number UNIQUE (account_number)
);

CREATE INDEX idx_accounts_account_type ON accounts (account_type);

CREATE TABLE journal_entries (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    reference_id  VARCHAR(128) NOT NULL,
    description   VARCHAR(512),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_journal_entries_reference_id UNIQUE (reference_id)
);

CREATE INDEX idx_journal_entries_created_at ON journal_entries (created_at);

-- Ledger entries are append-only and immutable: no update path is ever
-- provided for this table, and it deliberately has no balance column.
-- Balances are always derived by summing entries per account.
--
-- The invariant that a ledger_entry.currency must match its account's
-- currency cannot be expressed as a single-table CHECK constraint in
-- PostgreSQL; it is enforced by the posting service layer.
CREATE TABLE ledger_entries (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    journal_entry_id  UUID NOT NULL,
    account_id        UUID NOT NULL,
    entry_type        VARCHAR(16) NOT NULL,
    amount            NUMERIC(19, 4) NOT NULL,
    currency          VARCHAR(3) NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_ledger_entries_journal_entry
        FOREIGN KEY (journal_entry_id) REFERENCES journal_entries (id),
    CONSTRAINT fk_ledger_entries_account
        FOREIGN KEY (account_id) REFERENCES accounts (id),
    CONSTRAINT chk_ledger_entries_entry_type
        CHECK (entry_type IN ('DEBIT', 'CREDIT')),
    CONSTRAINT chk_ledger_entries_amount_positive
        CHECK (amount > 0)
);

CREATE INDEX idx_ledger_entries_journal_entry_id ON ledger_entries (journal_entry_id);
CREATE INDEX idx_ledger_entries_account_id ON ledger_entries (account_id);
CREATE INDEX idx_ledger_entries_created_at ON ledger_entries (created_at);
