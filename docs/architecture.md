# Architecture

This document is updated at the end of every phase. It reflects the state
of the system as of **Phase 3: REST API Layer**.

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
REST API (`/api/v1`) documented with OpenAPI/Swagger, with no Kafka
publishing or concurrency-specific locking yet (see Roadmap).

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

## Roadmap

- **Phase 0** (done): runnable scaffold, health endpoint, infra wiring.
- **Phase 1** (done): accounts, immutable ledger entries, base schema.
- **Phase 2** (done): balanced/idempotent posting engine, live balance
  derivation, service-layer validation.
- **Phase 3** (this phase): versioned REST API (`/api/v1`) over the
  existing posting/balance/query engine, centralized exception-to-HTTP
  mapping, OpenAPI/Swagger documentation, paginated ledger history.
- **Later phases**: Kafka event publishing on successful posts,
  concurrency handling (optimistic or pessimistic locking,
  isolation-level tuning) for concurrent postings against the same
  account.
