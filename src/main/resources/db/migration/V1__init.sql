-- Baseline migration. Establishes the migration history table and
-- enables the extension used for generating UUID primary keys in
-- future ledger schema migrations.
CREATE EXTENSION IF NOT EXISTS "pgcrypto";
