# Architecture

This document is updated at the end of every phase. It reflects the state
of the system as of **Phase 4: Concurrency and Transaction Isolation**.

## System overview

`double-entry-ledger` is a production-grade, immutable double-entry
bookkeeping service. Clients post complete, balanced accounting
transactions (`JournalEntry` + its `LedgerEntry` lines) over a versioned
REST API; the service validates and persists them atomically. Account
balances are never stored — they are always derived on demand by
aggregating `ledger_entries` in PostgreSQL. Posted records are immutable
and append-only: corrections happen by posting a new, separate correcting
transaction, never by editing or deleting history.

As of Phase 3, the service exposes its posting and query engine over a
REST API (`/api/v1`) documented with OpenAPI/Swagger. As of Phase 4, the
posting and balance-reading paths are proven correct under real concurrent
access (see "Concurrency" below); Kafka publishing is still deferred (see
Roadmap).

## Package structure

```
src/main/java/com/abel/ledger/
  LedgerApplication.java        application entrypoint
  controller/
    StatusController.java       liveness endpoint ("/"), unversioned
  config/
    OpenApiConfig.java          OpenAPI title/description bean
  domain/
    account/                    Account entity, AccountType enum
    journal/                    JournalEntry entity
    ledger/                     LedgerEntry entity, EntryType enum
    idempotency/                IdempotencyKey entity
  dto/                          SERVICE-layer DTOs (PostingRequest,
                                 LedgerEntryRequest, PostingResult,
                                 LedgerEntryResult, AccountBalance) —
                                 JPA entities are never exposed across a
                                 service boundary, and these are never
                                 reused directly as API request/response
                                 models (see api/dto below)
  exception/                    LedgerException hierarchy: domain-specific
                                 failures raised by the service layer
                                 (PostingService/BalanceService/
                                 LedgerQueryService)
  repository/                   Spring Data JPA repositories, one per
                                 aggregate (flat, not nested under domain/)
  service/
    PostingService.java         validates and atomically posts transactions
    BalanceService.java         derives account balances via live SQL aggregation
    LedgerQueryService.java     read-only journal entry / ledger entry lookups
  api/                          the REST layer — see "API layer structure" below
    controller/
      JournalEntryController.java
      AccountController.java
    dto/
      request/                  API-layer request DTOs (Jakarta Validation)
      response/                 API-layer response DTOs, incl. PagedResponse<T>
    mapper/
      LedgerApiMapper.java      API DTO <-> service DTO translation
    exception/
      GlobalExceptionHandler.java   @RestControllerAdvice, all exception->HTTP mapping
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
    PostingServiceConcurrencyTest.java   real ExecutorService concurrency
                                          tests (part of `mvn test`) — see
                                          "Concurrency" below
    PostingServiceStressCheck.java       manual/repeatable stress harness,
                                          deliberately NOT matching Surefire's
                                          default test-discovery patterns so
                                          `mvn test` never runs it — see
                                          "Concurrency" below
  api/controller/
    JournalEntryControllerIntegrationTest.java   MockMvc, real HTTP request/response
    AccountControllerIntegrationTest.java        MockMvc, real HTTP request/response
docs/
  architecture.md               this file
  posting-flow.md               ordered walkthrough of the posting flow
  api-examples.md               curl/HTTPie + JSON examples per endpoint
```

## API layer structure

Every request flows through the same four-stage pipeline:

```
Controller → API DTO → Mapper → Service DTO → Service
```

- **Controllers** (`api/controller`) do no business logic. They deserialize
  the API request DTO (validated by Jakarta Validation via `@Valid`),
  hand it to `LedgerApiMapper` to become a service-layer DTO, call
  `PostingService`/`BalanceService`/`LedgerQueryService`, and map the
  result back to an API response DTO. All exceptions propagate up to
  `GlobalExceptionHandler` — no controller catches anything.
- **API DTOs** (`api/dto/request`, `api/dto/response`) are a completely
  separate set of types from the Phase 2 service DTOs in `dto/`, even
  where their shape is currently identical (e.g. a ledger entry line).
  This means the public API contract can evolve — add a field, rename
  something, version a new representation — without touching
  `PostingService`/`BalanceService`, and vice versa. Request DTOs carry
  Jakarta Validation annotations (`@NotBlank`, `@Positive`, `@Size`,
  `@Digits`, `@Valid` for nested lists); response DTOs never expose a JPA
  entity.
- **`LedgerApiMapper`** is the single translation point between the two
  DTO families, in both directions (request → service DTO, service
  result → response DTO).
- **`PostingService`/`BalanceService`** are unchanged from Phase 2 — the
  REST layer was built entirely on top of them, per this phase's
  constraint. Read-only queries that Phase 2 didn't need
  (`GET /journal-entries/{id}`, paginated
  `GET /accounts/{id}/ledger`) live in a new `LedgerQueryService`
  rather than being bolted onto either existing service.
- **Pagination**: `AccountController#getLedger` accepts a Spring Data
  `Pageable` (page/size/sort query parameters, default
  `sort=createdAt,desc`) and calls `LedgerQueryService`, which returns a
  `Page<LedgerEntryResult>`. The controller never returns that `Page<T>`
  directly — it's wrapped in `PagedResponse<T>`
  (`content`, `page`, `size`, `totalElements`, `totalPages`, `hasNext`)
  so the API contract doesn't leak Spring Data's internal pagination
  model.

## HTTP status code reasoning: 400 vs. 422

The API distinguishes two different ways a request can be rejected:

- **400 Bad Request** — the request is malformed: a required field is
  missing/blank, a type doesn't match, a value fails a structural
  constraint (`@NotBlank`, `@Positive`, `@Size`, `@Digits`). These are
  caught either by Jakarta Validation before the controller body even
  runs (`MethodArgumentNotValidException`) or, as a defense-in-depth
  fallback, by `PostingService`'s own structural checks
  (`InvalidPostingRequestException`). The client sent something the API
  can't parse into a valid domain request at all.
- **422 Unprocessable Entity** — the request is syntactically valid (it
  passes every Jakarta Validation constraint and parses into a coherent
  `PostingRequest`) but violates a business rule once the service
  evaluates it: debits and credits don't balance
  (`UnbalancedJournalEntryException`) or an entry's currency doesn't
  match its account's currency, or entries mix currencies
  (`CurrencyMismatchException`). The request is well-formed; the
  transaction it describes just isn't a valid accounting entry.

`GlobalExceptionHandler` encodes this split directly: `InvalidPostingRequestException`
and `MethodArgumentNotValidException` map to 400; `UnbalancedJournalEntryException`
and `CurrencyMismatchException` map to 422.

One deliberate non-obvious choice: a replay of an already-processed
`idempotencyKey` with an *identical* payload is treated as another
successful creation — `POST /api/v1/journal-entries` returns `201 Created`
with the same `Location` and body both times, since `PostingService.post()`
gives the controller no signal distinguishing "just created" from
"returned an existing result," and the resource genuinely does exist at
that location either way.

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

No schema changes in Phase 3 — the REST layer is read/write access to the
same three tables above, via the same repositories.

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
- **Separate API and service DTOs, joined by one mapper** (Phase 3). See
  "API layer structure" above. The alternative — reusing `dto/`'s
  `PostingRequest`/`PostingResult` etc. directly as controller
  request/response models — was rejected because it would couple the
  public HTTP contract to internal service-layer shape, making either one
  harder to change independently.
- **400 vs. 422** (Phase 3). See "HTTP status code reasoning" above.
- **New `LedgerQueryService` instead of extending `PostingService`/
  `BalanceService`** (Phase 3). Phase 3's instructions were explicit:
  don't modify those two classes. `GET /journal-entries/{id}` and
  paginated `GET /accounts/{id}/ledger` are new read paths with no
  Phase 2 equivalent, so they got a new service rather than being
  force-fit into an existing one.
- **Known tradeoff: extra re-read after posting, to work around
  `@CreationTimestamp` flush timing** (Phase 3). `JournalEntry` and
  `LedgerEntry` both use Hibernate's `@CreationTimestamp`, which only
  populates `createdAt` when the row is actually flushed/inserted — and
  because `PostingService.post()` never explicitly flushes, that happens
  at transaction commit, *after* `post()` has already built and returned
  its `PostingResult`. A live smoke test against the running app showed
  this concretely: `POST /api/v1/journal-entries` came back with
  `"createdAt": null` on the journal entry and both lines, while an
  immediate `GET` on the same id returned real timestamps for the exact
  same row. `JournalEntryController.postJournalEntry` works around this
  by re-reading the journal entry through `LedgerQueryService` right
  after `postingService.post()` returns — by then the transaction has
  committed, so the re-read is guaranteed to see populated timestamps.
  This is a controller-layer workaround, not a root-cause fix: it costs
  one extra indexed read per post. The root-cause fix — setting
  `createdAt` explicitly in Java before persisting (the same treatment
  already given to the entity's UUID, which is client-generated rather
  than DB-generated) so the value is known immediately without a flush —
  would avoid that extra query entirely, but requires editing
  `PostingService`/the entities, which is out of scope for this phase.
  Worth revisiting when `PostingService` is next touched.
- **`PagedResponse<T>` instead of returning `Page<T>`** (Phase 3). Spring
  Data's `Page<T>` JSON representation carries Spring-internal fields
  (`pageable`, `sort` metadata shape, etc.) that aren't a contract anyone
  should depend on. `PagedResponse<T>` is a small, stable, hand-written
  shape (`content`, `page`, `size`, `totalElements`, `totalPages`,
  `hasNext`).
- **MockMvc for REST integration tests, not a real HTTP client** (Phase 3).
  `@SpringBootTest(webEnvironment = MOCK)` + `@AutoConfigureMockMvc` runs
  requests through the actual `DispatcherServlet`, filters, Jakarta
  Validation, and `GlobalExceptionHandler` — the same code path a real
  HTTP request takes — without binding a socket or needing a separate
  HTTP client dependency. This is the standard Spring Boot approach for
  controller-level tests and is faster and more deterministic than
  driving the app over a real port.
- **Kafka health indicator explicitly disabled** (Phase 3).
  `spring-kafka` has been a dependency since Phase 0 and Spring Boot
  auto-configures a `KafkaAdmin` bean whenever it's on the classpath,
  which in turn auto-registers a Kafka health indicator — even though no
  Kafka publishing exists yet. `management.health.kafka.enabled: false`
  suppresses it until Kafka is actually implemented, so
  `/actuator/health` doesn't report on a feature that doesn't exist.
  Database health is left enabled (`show-details: always`) since a real
  `DataSource` bean has existed since Phase 1.
- **No elevated isolation or explicit locking for posting/balance
  correctness** (Phase 4). See "Concurrency" below for the full analysis.
  In short: this schema has no stored balance column and no update path
  anywhere, so the lost-update races that usually justify pessimistic or
  optimistic locking don't exist here. The one real race — concurrent
  reuse of the same `idempotencyKey` — is closed by the unique constraint
  that already existed since Phase 2, plus a catch-and-replay recovery
  added in `PostingService.post()` this phase so the losing request
  gets the winner's result instead of an unhandled 500.

## Concurrency

Phase 4's job was to guarantee correctness of the posting and
balance-reading paths under real concurrent access, and prove it with real
concurrency tests — not to assume that locking or an elevated isolation
level is necessary and bolt one on. This section walks through the actual
risk analysis for this specific schema, what was (and wasn't) done about
it, and how it's tested.

### Correctness requirements

Under concurrent access, the system must guarantee:

1. No duplicate `JournalEntry` rows for the same `idempotencyKey`, no
   matter how many concurrent requests use it simultaneously.
2. No partial transactions — a `JournalEntry` and all its `LedgerEntry`
   rows either fully exist or don't exist at all.
3. No orphan `LedgerEntry` rows.
4. Every `JournalEntry` remains balanced (debits equal credits).
5. Derived balances remain correct under concurrent posting.

### Why this schema has less concurrency risk than a typical ledger

The classic ledger concurrency hazard — two concurrent transactions both
reading a stored `balance` column, computing `balance + delta` in
application code, and racing to write it back (a lost update) — cannot
happen here, because **there is no stored balance column** (see "No stored
balance column" below). `BalanceService` always computes a live
`SUM(...)` over `ledger_entries`. There is no shared mutable row
representing "this account's balance" for two postings to contend over;
each posting only ever *inserts* new, immutable rows. This one fact is
why most of the traditional locking toolbox (`SELECT ... FOR UPDATE` on
the account, an optimistic `@Version` column on `Account`, escalating to
`SERIALIZABLE`) turns out to solve a problem this design doesn't have.

Walking through the five requirements against plain `READ COMMITTED` (the
Postgres default, never overridden anywhere in this codebase) and the
constraints already in place since earlier phases:

- **Requirement 2 (no partial transactions)** is guaranteed by ordinary
  transaction atomicity, which Postgres provides at every isolation
  level, not just elevated ones. `PostingService.postWithinTransaction`
  remains the sole `@Transactional` write boundary: the `JournalEntry`,
  its `LedgerEntry` rows, and the `IdempotencyKey` row are all written in
  one transaction, so any failure — a validation error, a constraint
  violation, anything — rolls back every write from that attempt. This
  was already true as of Phase 2 and needed no changes.
- **Requirement 3 (no orphan `LedgerEntry` rows)** follows from the same
  atomicity, backed by the `fk_ledger_entries_journal_entry` foreign key
  from the V2 migration: a `LedgerEntry` can only be persisted alongside
  the `JournalEntry` it references, in the same transaction, or not at
  all.
- **Requirement 4 (every `JournalEntry` balanced)** is a property of the
  *input* to a single posting request (`validateBalanced` sums the
  request's own debit/credit entries before anything is persisted) — it
  isn't derived from any state another concurrent transaction could be
  mutating, so there's nothing for a race to corrupt. `LedgerEntry` rows
  are immutable and never updated after insert, so a balanced entry
  cannot become unbalanced later, concurrently or otherwise.
- **Requirement 5 (derived balances correct under concurrent posting)**
  follows from Postgres never permitting dirty reads (there is no
  `READ UNCOMMITTED` in Postgres; `READ COMMITTED` is its weakest level)
  combined with each `JournalEntry` + its `LedgerEntry` rows committing
  atomically as one unit. A concurrent `BalanceService.getBalance` call
  will only ever see the fully-committed effect of a posting or none of
  it — never one of its two legs without the other. A balance read that
  overlaps an in-flight commit may or may not reflect that specific
  posting yet (ordinary read-committed staleness, not a correctness
  bug); once the posting commits, every subsequent read reflects it.
  `PostingServiceConcurrencyTest.balanceReadsDuringConcurrentPostingNeverObserveATornOrNegativeState`
  asserts exactly this: every balance observed while postings are firing
  concurrently is a non-negative whole multiple of the posting increment
  — never a torn, fractional value that would mean a read saw one leg of
  a journal entry without the other.
- **Requirement 1 (no duplicate `JournalEntry` per `idempotencyKey`)** is
  the one requirement that isn't free under `READ COMMITTED` — see below.

### The one real race, and how it's closed

`PostingService.post()`'s idempotency check is check-then-act:
`findByIdempotencyKey` is read, and only if it's empty does the method go
on to insert a new `JournalEntry`, its `LedgerEntry` rows, and a new
`IdempotencyKey` row. Under `READ COMMITTED`, two concurrent requests
carrying the same `idempotencyKey` can both read "not found" before
either commits — that gap is inherent to check-then-act under
`READ COMMITTED` and would exist under `REPEATABLE READ` too (closing it
would require `SERIALIZABLE`, discussed and rejected below).

What actually prevents a duplicate row from ever being **persisted** is
the pre-existing unique constraint from the Phase 2 migration,
`uq_idempotency_keys_key` on `idempotency_keys.idempotency_key`
(`V3__create_idempotency_keys.sql`). One of the two racing transactions
commits first; the second's insert into `idempotency_keys` then violates
that constraint, and — because `postWithinTransaction` is the sole
transactional boundary — its *entire* transaction rolls back, taking its
`JournalEntry` and `LedgerEntry` rows down with it. So requirement 1 was
already structurally guaranteed before Phase 4: **no unique constraint
was added in this phase** — `uq_idempotency_keys_key` already existed.

What Phase 4 actually had to fix was the *experience* of losing that
race: without any handling, the losing request's caller — who did
nothing wrong, and in the common case (a client retrying the exact same
request after a timeout) is asking for the exact same outcome the first
request already produced — would see a raw `DataIntegrityViolationException`
surface as an unhandled 500, instead of the same successful result the
winner got.

`PostingService.post()` now wraps the transactional attempt and recovers:

```java
public PostingResult post(PostingRequest request) {
    validateStructure(request);
    try {
        return self.postWithinTransaction(request);
    } catch (DataIntegrityViolationException ex) {
        return self.recoverFromConcurrentIdempotencyKeyInsert(request, ex);
    }
}
```

`recoverFromConcurrentIdempotencyKeyInsert` looks up
`idempotency_keys` for *this request's own* `idempotencyKey`. If a row
exists — the winner committed it — it replays that result (verifying the
fingerprint matches first, exactly like the pre-existing sequential
replay path; a fingerprint mismatch still throws
`IdempotencyKeyConflictException`, unchanged from Phase 2). If no such
row exists, the failure had nothing to do with a race on this key, and
the original exception is rethrown unchanged.

**A subtlety found while writing the concurrency tests**: the recovery
logic originally matched on the specific unique-constraint name
(`uq_idempotency_keys_key`), reasoning that only that constraint's
violation should trigger a replay. That was too narrow. When two racing
requests share both the same `idempotencyKey` *and* the same
`referenceId` (the realistic case — a client retrying its own request
still sends the same business reference), the `JournalEntry` insert
happens *before* the `IdempotencyKey` insert in `postWithinTransaction`,
so the loser trips `uq_journal_entries_reference_id` first and never
even reaches the idempotency-key insert. Matching by constraint name
missed this and let the exception escape. The fix was to stop caring
*which* constraint fired and instead check the actually-relevant
question directly: does a committed `idempotency_keys` row for *this
key* exist now? That check is correct regardless of which insert failed
first, and is exactly what
`PostingServiceConcurrencyTest.concurrentPostingsWithSameIdempotencyKeySimulateRetryStormWithoutDuplicating`
exists to catch a regression of.

This recovery requires calling the `@Transactional` methods
(`postWithinTransaction`, `recoverFromConcurrentIdempotencyKeyInsert`)
through Spring's proxy rather than via a plain `this.` call from `post()`
— a direct `this.` call would silently bypass the proxy (and the
transaction along with it), a classic Spring AOP self-invocation trap.
`PostingService` injects a `@Lazy` self-reference for this
(`self.postWithinTransaction(...)`); the `@Lazy` breaks the circular
dependency this would otherwise create. A second, 4-argument constructor
(delegating to the real one with `self = null`, which then falls back to
`this`) is kept purely so `PostingServiceTest`'s existing
`new PostingService(...)` construction with mocked repositories keeps
working unchanged — in that context there's no real transactional proxy
involved either way, so `self == this` is the correct, harmless fallback.

### Mechanisms considered and deliberately NOT used

- **Elevated isolation (`REPEATABLE READ` / `SERIALIZABLE`) for
  posting.** Rejected. Nothing in `postWithinTransaction` reads a value,
  computes a derived result from it, and writes based on that read the
  way a lost-update-prone flow would (e.g. read balance, compute new
  balance, write it back). Every write is a fresh insert of data the
  request itself provided; the only cross-transaction interaction is the
  idempotency-key race above, which a unique constraint plus
  catch-and-replay already closes more cheaply and without the
  serialization-failure retry burden `SERIALIZABLE` would add.
- **Pessimistic locking (`SELECT ... FOR UPDATE`) on `Account`.**
  Rejected. This would serialize all postings touching a given account
  through a single lock, which is exactly the kind of unnecessary
  contention the task asked to avoid — and it wouldn't even be
  protecting anything, since `Account` rows are never written by
  `PostingService` (no balance column to protect).
  `PostingServiceConcurrencyTest.concurrentPostingsWithDistinctIdempotencyKeysAgainstSameAccountsStayConsistent`
  fires 32 fully-concurrent postings at the same account pair with no
  locking at all and asserts the resulting balances and invariants are
  exactly right — demonstrating the lock would have bought nothing.
- **Optimistic locking (a `@Version` column) on `Account` or
  `JournalEntry`.** Rejected for the same reason: optimistic locking
  detects a *lost update to a row a transaction previously read*, and no
  transaction in this codebase ever reads an `Account` or `JournalEntry`
  row and then writes an update back to it — everything is
  insert-only and immutable. There is no update path anywhere in this
  schema for a `@Version` column to protect.
- **A unique constraint beyond what already existed.** Not needed.
  `uq_idempotency_keys_key` and `uq_journal_entries_reference_id` both
  predate this phase (Phase 2/Phase 1 migrations) and already provide
  every guarantee requirement 1 needs; Phase 4 added no new migration.

### Transaction retry

The chosen strategy — `READ COMMITTED`, no elevated isolation, no
explicit locks — **cannot produce Postgres serialization failures**
(SQLState `40001`), since those are only possible under `REPEATABLE READ`
or `SERIALIZABLE`, neither of which is used anywhere in this codebase. No
generic "retry the transaction" loop was added, because there is nothing
of that shape to retry: a transaction here either succeeds outright or
fails for a reason that would fail identically on immediate retry (a
genuine validation error, a genuine `reference_id` conflict with an
unrelated request) — except for the one specific, deterministic case
covered above (the idempotency-key race), which isn't handled by
blindly retrying the write but by directly fetching and replaying the
winner's already-committed result. That is a one-step, deterministic
recovery, not a retry loop, and is exactly what
`recoverFromConcurrentIdempotencyKeyInsert` does.

Ordinary Postgres deadlocks (`40P01`) are, in principle, possible in any
concurrent relational workload, but this schema's write pattern — every
transaction only ever *inserts* new rows and takes non-conflicting shared
foreign-key locks on already-committed, never-updated `Account`/
`JournalEntry` rows — doesn't create the lock-upgrade contention
(shared→exclusive) that causes real deadlocks. No deadlock was observed
across any concurrency or stress test run in this phase. No
deadlock-specific retry logic was added; if this ever needs revisiting,
the signal to watch for is `40P01` SQLState errors in production logs.

### Connection pool

`spring.datasource.hikari.maximum-pool-size` was raised from Hikari's
default of 10 to `20` in `application.yml`. This is unrelated to
correctness (nothing above depends on pool size) but matters for
throughput: a posting engine meant to accept concurrent write traffic
should not bottleneck on a pool sized for a low-traffic default. Requests
beyond the pool size simply queue for a connection rather than failing;
the stress test below intentionally sends far more concurrent requests
(50) than the pool can serve at once, to exercise that queuing path
under load.

### Concurrency tests

`PostingServiceConcurrencyTest` (part of the normal `mvn test` run, real
Postgres via the docker-compose stack — same convention as
`PostingServiceIntegrationTest`) fires real concurrent requests through a
real `ExecutorService`, gated by a `CountDownLatch` so all threads submit
at (as close to) the same instant rather than trickling in sequentially.
Each test loops 5 iterations with fresh accounts and keys per iteration,
since a concurrency bug is probabilistic and can pass by luck on a single
run:

1. **`concurrentPostingsWithDistinctIdempotencyKeysAgainstSameAccountsStayConsistent`**
   — 32 concurrent postings, distinct `idempotencyKey`s, same account
   pair. Asserts 32 distinct `JournalEntry`s result and both accounts'
   derived balances equal the expected total.
2. **`concurrentPostingsWithSameIdempotencyKeySimulateRetryStormWithoutDuplicating`**
   — 32 concurrent postings, the *same* `idempotencyKey` and payload
   (a double-click / retry-storm simulation). Asserts all 32 calls
   converge on exactly one `JournalEntry`, exactly one `idempotency_keys`
   row exists for that key, and exactly one pair of `LedgerEntry` rows
   was created (not 32 pairs).
3. **`balanceReadsDuringConcurrentPostingNeverObserveATornOrNegativeState`**
   — posting and balance-reading happen concurrently: 4 reader threads
   continuously call `BalanceService.getBalance` while 32 postings fire.
   Every observed balance must be non-negative and a whole multiple of
   the posting increment (a fractional value would mean a read saw one
   leg of a journal entry without the other).

After every iteration, all three tests call a shared
`assertLedgerInvariants` helper that queries Postgres directly (via
`JdbcTemplate`, not the application's own repositories) for:

- Any `JournalEntry` whose summed debits and credits don't match.
- Any `LedgerEntry` whose `journal_entry_id` doesn't resolve to an
  existing `JournalEntry` (orphan check).
- Any `idempotency_key` value appearing more than once.
- Each involved account's `BalanceService`-derived balance against an
  independently-computed direct SQL sum of its `ledger_entries`.

### Stress test

`PostingServiceStressCheck` (`src/test/java/com/abel/ledger/service/`)
fires 2000 concurrent posting requests, 50 at a time, at a single fresh
account pair, then runs the same `assertLedgerInvariants` checks. It's
deliberately named so it does **not** match maven-surefire-plugin's
default test-discovery patterns (`**/*Test.java`, `**/*Tests.java`,
`**/*TestCase.java`) — `mvn test` never picks it up on its own, since it's
meant for manual/exploratory load verification, not fast CI feedback.

To run it (`docker compose up -d postgres` must be running locally):

```
./mvnw test -Dtest=PostingServiceStressCheck
```

To scale the load, adjust `REQUEST_COUNT` / `CONCURRENCY` at the top of
the class. A representative run against this environment's docker-compose
Postgres:

```
2000 requests, concurrency 50, 2000 succeeded, 0 failed,
~2100-2800 ms elapsed (~720-940 req/s)
```

with all ledger invariants holding afterward.

## Roadmap

- **Phase 0** (done): runnable scaffold, health endpoint, infra wiring.
- **Phase 1** (done): accounts, immutable ledger entries, base schema.
- **Phase 2** (done): balanced/idempotent posting engine, live balance
  derivation, service-layer validation.
- **Phase 3** (done): versioned REST API (`/api/v1`) over the existing
  posting/balance/query engine, centralized exception-to-HTTP mapping,
  OpenAPI/Swagger documentation, paginated ledger history.
- **Phase 4** (this phase): proved posting/balance-reading correctness
  under real concurrent access; closed the one real race (concurrent
  `idempotencyKey` reuse) with a catch-and-replay recovery on top of the
  pre-existing unique constraint; documented why no elevated isolation or
  explicit locking is needed for this append-only, no-stored-balance
  schema; added `PostingServiceConcurrencyTest` and the manual
  `PostingServiceStressCheck`.
- **Later phases**: Kafka event publishing on successful posts.
