# Architecture

This document is updated at the end of every phase. It reflects the state
of the system as of **Phase 2: Ledger Posting Engine**.

## System overview

`double-entry-ledger` is a production-grade, immutable double-entry
bookkeeping service. Clients post complete, balanced accounting
transactions (`JournalEntry` + its `LedgerEntry` lines); the service
validates and persists them atomically. Account balances are never
stored — they are always derived on demand by aggregating `ledger_entries`
in PostgreSQL. Posted records are immutable and append-only: corrections
happen by posting a new, separate correcting transaction, never by editing
or deleting history.

As of Phase 2, the service has a complete service-layer posting and
balance-derivation engine with no HTTP surface yet (see Roadmap).

## Package structure

```
src/main/java/com/abel/ledger/
  LedgerApplication.java        application entrypoint
  controller/
    StatusController.java       liveness endpoint ("/")
  domain/
    account/                    Account entity, AccountType enum
    journal/                    JournalEntry entity
    ledger/                     LedgerEntry entity, EntryType enum
    idempotency/                IdempotencyKey entity
  dto/                          request/response DTOs for the service layer
                                 (PostingRequest, LedgerEntryRequest,
                                 PostingResult, LedgerEntryResult,
                                 AccountBalance) — JPA entities are never
                                 exposed across a service boundary
  exception/                    LedgerException hierarchy: domain-specific
                                 failures raised by PostingService/BalanceService
  repository/                   Spring Data JPA repositories, one per
                                 aggregate (flat, not nested under domain/)
  service/
    PostingService.java         validates and atomically posts transactions
    BalanceService.java         derives account balances via live SQL aggregation
src/main/resources/
  application.yml               base configuration
  application-dev.yml           local development overrides
  db/migration/                 Flyway migrations
src/test/java/com/abel/ledger/
  LedgerApplicationTests.java   context load + smoke tests
  service/
    PostingServiceTest.java              unit tests, mocked repositories
    BalanceServiceTest.java              unit tests, mocked repositories
    PostingServiceIntegrationTest.java   integration tests, real Postgres
    BalanceServiceIntegrationTest.java   integration tests, real Postgres
docs/
  architecture.md               this file
  posting-flow.md               ordered walkthrough of the posting flow
```

## Database schema

Four tables, managed by Flyway migrations in
`src/main/resources/db/migration/`:

- **V1__init.sql** — enables the `pgcrypto` extension (used for
  `gen_random_uuid()` primary keys).
- **V2__create_ledger_schema.sql**
  - `accounts` (`id`, `account_number` unique, `account_name`,
    `account_type`, `currency`, `created_at`)
  - `journal_entries` (`id`, `reference_id` unique, `description`,
    `created_at`) — `reference_id` is a business reference for the
    transaction, distinct from `idempotency_keys.idempotency_key` (see
    below)
  - `ledger_entries` (`id`, `journal_entry_id` FK, `account_id` FK,
    `entry_type` CHECK'd to `DEBIT`/`CREDIT`, `amount` `NUMERIC(19,4)`
    CHECK'd `> 0`, `currency`, `created_at`) — append-only, no update
    path exists anywhere in the application; deliberately has no balance
    column
- **V3__create_idempotency_keys.sql**
  - `idempotency_keys` (`id`, `idempotency_key` unique, `request_fingerprint`
    — a SHA-256 hex digest of the request payload, `journal_entry_id` FK,
    `created_at`)

Entry currency is validated against its account's currency in the service
layer (`PostingService`), not the schema — PostgreSQL has no clean way to
express a cross-table CHECK constraint for this.

## Key design decisions

- **No stored balance column** (Phase 1). Balances are always derived by
  summing `ledger_entries`, never cached or persisted, so they can never
  drift from the entries that back them.
- **Testcontainers tradeoff** (Phase 0/1). This environment's Docker
  Desktop installation negotiates a Docker Engine API version
  (1.55, min supported 1.40) that the bundled `docker-java` client in
  Testcontainers 1.20.3 rejects when it tries to negotiate API v1.32.
  Rather than pin to an older Testcontainers/docker-java combination,
  integration tests run against the Postgres stack started via
  `docker compose up`, which must be running locally. See
  `LedgerApplicationTests` for the full note.
- **`idempotencyKey` vs. `reference_id`** (Phase 2). These are modeled as
  two separate concepts in two separate tables rather than one column
  serving double duty. `reference_id` is the caller's business identifier
  for the transaction (e.g. an invoice number) and is enforced unique at
  the database level via `journal_entries.reference_id`. `idempotencyKey`
  is a client-supplied retry-safety token: submitting the same key with
  the same payload replays the original result; submitting it with a
  different payload is rejected as a likely client bug
  (`IdempotencyKeyConflictException`). Conflating the two would make it
  impossible to reuse a business reference across a legitimate
  correction/reversal flow. See the V3 migration and `IdempotencyKey` for
  the full rationale.
- **Single transactional entry point** (Phase 2). `PostingService.post()`
  is the only `@Transactional` method in the class; every validation step
  runs before any write, and persistence of the `JournalEntry`, its
  `LedgerEntries`, and the `IdempotencyKey` record all happen in that one
  transaction, so a failure at any point rolls back the entire attempt.
  No optimistic/pessimistic locking or custom isolation tuning is applied
  yet — standard `@Transactional` atomicity is sufficient because this
  phase has no concurrent-write requirements. That is deferred to a
  dedicated concurrency phase.
- **No multi-currency journal entries** (Phase 2). Every entry in a single
  `PostingRequest` must share one currency, and each entry's currency must
  match its account's currency. Cross-currency (FX) postings are out of
  scope for this phase; supporting them would require deciding how to
  express "balanced" across currencies, which is a separate design
  problem.
- **Live balance aggregation via native SQL** (Phase 2). `BalanceService`
  never loads `LedgerEntry` rows into memory; it calls a native
  `SUM/CASE` query (`LedgerEntryRepository.sumEntriesByAccountId`) so
  PostgreSQL does the aggregation. The normal balance convention
  (debit-normal: `ASSET`, `EXPENSE`; credit-normal: `LIABILITY`, `EQUITY`,
  `REVENUE`) is applied in `BalanceService`, not the query, keeping the
  repository purely mechanical.

## Roadmap

- **Phase 0** (done): runnable scaffold, health endpoint, infra wiring.
- **Phase 1** (done): accounts, immutable ledger entries, base schema.
- **Phase 2** (this phase): balanced/idempotent posting engine, live
  balance derivation, service-layer validation.
- **Later phases**: REST API surface for posting and querying, Kafka event
  publishing on successful posts, concurrency handling (optimistic or
  pessimistic locking, isolation-level tuning) for concurrent postings
  against the same account.
