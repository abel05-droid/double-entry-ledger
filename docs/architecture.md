# Architecture

This document is updated at the end of every phase. It reflects the state
of the system as of **Phase 6: Observability**.

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
access (see "Concurrency" below). As of Phase 5, every successfully
committed `JournalEntry` publishes a Kafka event so downstream systems can
react to ledger activity without coupling to the posting path directly
(see "Event Publishing" below); no consumer exists yet (see Roadmap). As
of Phase 6, the running application is inspectable from the outside:
structured logs correlate every log line to the request that produced it,
Micrometer metrics answer specific operational questions about posting,
balance reads, and Kafka publishing, and `/actuator/health` distinguishes
database and Kafka availability as separate components (see
"Observability" below).

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
    user/                       User entity, Role enum (ADMIN/VIEWER) —
                                 see "Authentication and Authorization"
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
  event/
    JournalEntryPostedEvent.java   plain-Java domain event, no Kafka
                                    dependency — see "Event Publishing" below
  repository/                   Spring Data JPA repositories, one per
                                 aggregate (flat, not nested under domain/),
                                 including UserRepository
  service/
    PostingService.java         validates and atomically posts transactions;
                                 raises JournalEntryPostedEvent on success
    BalanceService.java         derives account balances via live SQL aggregation
    LedgerQueryService.java     read-only journal entry / ledger entry lookups
  kafka/                        turns domain events into Kafka messages —
                                 see "Event Publishing" below
    LedgerKafkaTopics.java         topic name constants
    JournalEntryPostedMessage.java the public JSON wire schema (eventId,
                                    eventVersion, journalEntryId, referenceId,
                                    postedAt, affectedAccountIds)
    LedgerKafkaProducerConfig.java dedicated ProducerFactory/KafkaTemplate
                                    beans, explicit producer configuration
    LedgerEventPublisher.java      @TransactionalEventListener(AFTER_COMMIT);
                                    the only class that knows about Kafka
                                    message shape/topic/key; also records
                                    ledger.kafka.publish.* metrics and
                                    structured logs — see "Observability"
  observability/                 cross-cutting instrumentation — see
                                  "Observability" below
    CorrelationIdFilter.java       reads/generates X-Correlation-ID, puts
                                    it in MDC, echoes it in the response
    Uuid7Generator.java             hand-rolled RFC 9562 UUIDv7 generator
                                    (java.util.UUID has no v7 support)
    PostingObservabilityAspect.java @Around advice on PostingService.post /
                                    recoverFromConcurrentIdempotencyKeyInsert
                                    — zero changes to PostingService itself
    BalanceObservabilityAspect.java @Around advice on
                                    BalanceService.getBalance
    KafkaHealthIndicator.java      custom /actuator/health "kafka" component
  security/                      stateless JWT auth + role authorization —
                                  see "Authentication and Authorization"
    SecurityConfig.java             SecurityFilterChain, PasswordEncoder,
                                     AuthenticationManager beans
    JwtService.java                 issues/verifies HS256 JWTs
    JwtProperties.java               jwt.secret / jwt.expiration binding
    JwtAuthenticationFilter.java    populates SecurityContext from a
                                     Bearer token, or leaves it anonymous
    CustomUserDetailsService.java   UserDetailsService backed by UserRepository
    RestAuthenticationEntryPoint.java  401 for missing/invalid/expired tokens
    RestAccessDeniedHandler.java       403 for insufficient role
  api/                          the REST layer — see "API layer structure" below
    controller/
      JournalEntryController.java
      AccountController.java
      AuthController.java         POST /api/v1/auth/login
    dto/
      request/                  API-layer request DTOs (Jakarta Validation),
                                 incl. LoginRequest
      response/                 API-layer response DTOs, incl.
                                 PagedResponse<T> and LoginResponse
    mapper/
      LedgerApiMapper.java      API DTO <-> service DTO translation
    exception/
      GlobalExceptionHandler.java   @RestControllerAdvice, all exception->HTTP mapping
src/main/resources/
  application.yml               base configuration
  application-dev.yml           local development overrides
  logback-spring.xml            plain-text (default) vs. JSON (json-logs
                                 profile) log output — see "Observability"
  db/migration/                 Flyway migrations (V4 adds users, seeded
                                 with the ADMIN/VIEWER demo accounts)
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
  kafka/
    LedgerEventPublisherTest.java             unit test, mocked KafkaTemplate
    LedgerEventPublishingIntegrationTest.java  @EmbeddedKafka integration
                                                tests — see "Event Publishing"
                                                below
  observability/
    CorrelationIdFilterIntegrationTest.java   MockMvc — header round-trip,
                                               generated-id UUIDv7 validity,
                                               MDC cleared after the request
    Uuid7GeneratorTest.java                   version/variant bits, uniqueness
    PostingObservabilityIntegrationTest.java  metrics + ListAppender-captured
                                               structured log fields for the
                                               posting path
    BalanceObservabilityIntegrationTest.java  metrics for the balance-read path
    KafkaHealthIndicatorTest.java             UP (real broker) / DOWN
                                               (unreachable address) health
    HealthEndpointIntegrationTest.java        MockMvc — /actuator/health
                                               component-level status
  api/controller/
    JournalEntryControllerIntegrationTest.java   MockMvc, real HTTP request/response
    AccountControllerIntegrationTest.java        MockMvc, real HTTP request/response
  security/
    SecurityIntegrationTest.java   MockMvc — login, malformed/expired/missing
                                    JWT, role-based 401 vs. 403, public paths
docs/
  architecture.md               this file
  posting-flow.md               ordered walkthrough of the posting flow
  api-examples.md               curl/HTTPie + JSON examples per endpoint
  load-testing.md               hey-based load test, usage + measured results
.github/workflows/
  ci.yml                        test (real Postgres/Kafka) + dependency-check
                                 + Docker build/Trivy-scan jobs — see
                                 "Production Readiness"
scripts/
  load-test.sh                  see docs/load-testing.md
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

Five tables, managed by Flyway migrations in
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
- **V4__create_users.sql**
  - `users` (`id`, `username` unique, `password_hash` — BCrypt, never
    plaintext, `role` CHECK'd to `ADMIN`/`VIEWER`, `created_at`) — seeds
    one `ADMIN` and one `VIEWER` demo user; see "Authentication and
    Authorization" below and the README for the credentials

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
- **`@TransactionalEventListener(AFTER_COMMIT)` instead of publishing to
  Kafka inline in `PostingService`** (Phase 5). See "Event Publishing"
  below for the full analysis. In short: `PostingService` raises a plain
  Java domain event through Spring's `ApplicationEventPublisher` and stays
  entirely unaware of Kafka; a separate listener converts that event into
  a Kafka message only once Spring confirms the transaction actually
  committed, which is exactly the guarantee "publish iff committed" needs
  without any manual rollback-detection code.
- **AOP for `PostingService`/`BalanceService` observability, direct
  instrumentation for `LedgerEventPublisher`'s** (Phase 6). See
  "Observability" below. In short: `PostingObservabilityAspect` and
  `BalanceObservabilityAspect` add metrics and structured logging around
  synchronous, easily-wrapped method calls without a single line of diff
  to `PostingService.java`/`BalanceService.java` — the strongest possible
  proof that no behavior changed. `LedgerEventPublisher`'s Kafka publish
  outcome is determined asynchronously inside a `CompletableFuture`
  callback, which an `@Around` advice around the method call cannot
  observe (the method returns long before the callback fires), so that
  one class is directly instrumented instead — still purely additive,
  per this phase's explicit allowance for "logging statements, metric
  recording" in that class.

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

## Event Publishing

Phase 5's job was to publish a Kafka event whenever a `JournalEntry` is
successfully posted, so downstream systems could eventually react to
ledger activity without coupling to the posting path directly —
publishing only, no consumer.

### Correctness requirements

1. An event is published if and only if the `JournalEntry`'s transaction
   actually committed. A rolled-back posting attempt (unbalanced entries,
   currency mismatch, a concurrency-race loser, or any other failure)
   must never result in a published event.
2. Publishing failures must never cause a successful posting operation to
   appear failed to the caller — the HTTP response for a successful post
   does not depend on whether the Kafka publish succeeded.
3. An idempotent replay (same `idempotencyKey`, same payload, no new row
   created) must not publish a duplicate event for the same `JournalEntry`.

### Domain event vs. Kafka event: why two types, not one

`PostingService` raises `com.abel.ledger.event.JournalEntryPostedEvent` —
a plain Java record (`journalEntryId`, `referenceId`, `postedAt`,
`affectedAccountIds`) with no Kafka, JSON, or messaging dependency of any
kind — through Spring's own `ApplicationEventPublisher`. `PostingService`
has no dependency on Kafka producer types, `spring-kafka`, or even the
concept of a "topic"; it only knows that "a journal entry was posted" is
a fact worth telling the rest of the application about.

`com.abel.ledger.kafka.LedgerEventPublisher` is a separate `@Component`
that listens for that domain event and is solely responsible for turning
it into `com.abel.ledger.kafka.JournalEntryPostedMessage` (the actual
Kafka JSON payload, which additionally carries `eventId` and
`eventVersion` — publishing-mechanism concerns that don't belong on the
domain event) and sending it to Kafka. This split means: if a second
consumer of "a journal entry was posted" ever needs to exist in-process
(e.g. a future audit-log writer), it can listen for the same domain event
without touching `PostingService` or knowing Kafka is involved at all;
and `PostingService` unit tests (`PostingServiceTest`) can assert an
event was raised using a plain mocked `ApplicationEventPublisher`, with
no Kafka test infrastructure whatsoever.

### The after-commit mechanism: `@TransactionalEventListener(phase = AFTER_COMMIT)`

This is the single mechanism that satisfies correctness requirement 1,
and it's a built-in Spring primitive, not custom code.
`PostingService.postWithinTransaction` calls
`eventPublisher.publishEvent(...)` as its last step, immediately after
the `IdempotencyKey` row is saved — while its surrounding
`@Transactional` transaction is still open, i.e. *before* that
transaction has actually committed. Spring does not invoke a
`@TransactionalEventListener(phase = AFTER_COMMIT)` method synchronously
at `publishEvent()` time; instead it registers a
`TransactionSynchronization` against the current transaction and only
invokes the listener from that synchronization's `afterCommit()`
callback — which Spring only calls once the transaction has actually
committed successfully. If the transaction instead rolls back for any
reason (an unbalanced-entries or currency-mismatch validation failure,
or the concurrency-race loser described in "Concurrency" above, whose
`postWithinTransaction` call rolls back before ever raising the event, or
rolls back *after* raising it but before commit — either way), Spring
discards the queued event without ever invoking `LedgerEventPublisher`.
No manual "did this actually commit" bookkeeping is needed anywhere in
this codebase; the annotation *is* the mechanism.

Requirement 3 (no duplicate event on idempotent replay) falls out of the
same design without any extra idempotency check in the Kafka layer:
`PostingService`'s `replay()` method — used both for a same-transaction
idempotency-key hit and for `recoverFromConcurrentIdempotencyKeyInsert`'s
recovery path — never calls `publishEvent()` at all. A replay simply
never reaches the one line in `postWithinTransaction` that raises the
event, so there is nothing for `LedgerEventPublisher` to receive twice.
`LedgerEventPublishingIntegrationTest.publishesExactlyOneEventAcrossAnIdempotentReplay`
proves this directly: posting the same request twice yields exactly one
Kafka record.

Requirement 2 (a publish failure can't fail a successful post) is
satisfied by `LedgerEventPublisher.onJournalEntryPosted` never throwing:
every path — a synchronous exception from `kafkaTemplate.send(...)`
(e.g. a serialization failure) and an asynchronous failure surfaced via
the returned `CompletableFuture` (e.g. the broker being unreachable) — is
caught and logged, never rethrown. Combined with the fact that
`KafkaTemplate.send()` itself is non-blocking (it hands off to the
producer's internal buffer and returns a future immediately, rather than
blocking on a broker acknowledgment), the listener adds negligible
latency to the request and can never turn a committed post into an
apparent failure. `LedgerEventPublisherTest` proves this at the unit
level with a mocked `KafkaTemplate` that throws synchronously and one
that returns an exceptionally-completed future — both leave
`onJournalEntryPosted` returning normally.

### Event flow

```
POST /api/v1/journal-entries
        │
        ▼
PostingService.post()
        │
        ▼
postWithinTransaction()  (@Transactional)
  ├─ persist JournalEntry
  ├─ persist LedgerEntry rows
  ├─ persist IdempotencyKey
  └─ eventPublisher.publishEvent(JournalEntryPostedEvent)
        │  (queued against the current transaction, not delivered yet)
        ▼
   ── transaction commits ──
        │
        ▼
LedgerEventPublisher.onJournalEntryPosted()   (@TransactionalEventListener,
        │                                       phase = AFTER_COMMIT)
        ▼
builds JournalEntryPostedMessage (eventId, eventVersion, journalEntryId,
                                   referenceId, postedAt, affectedAccountIds)
        │
        ▼
KafkaTemplate<String, JournalEntryPostedMessage>.send(...)
        │  key = journalEntryId.toString()
        ▼
   ledger.journal-entry.posted.v1
```

If `postWithinTransaction` throws instead of reaching the commit step
(unbalanced entries, currency mismatch, an unrelated constraint
violation, or the transaction otherwise failing to commit), the flow
stops before "transaction commits" and `LedgerEventPublisher` is never
invoked — no message ever reaches the topic.

### Event schema and versioning

`JournalEntryPostedMessage` (`com.abel.ledger.kafka`) is the JSON payload:

```json
{
  "eventId": "b3f1...",
  "eventVersion": 1,
  "journalEntryId": "a1c2...",
  "referenceId": "REF-2026-001",
  "postedAt": "2026-07-09T02:35:28.681Z",
  "affectedAccountIds": ["8d8e...", "2e70..."]
}
```

Deliberately excluded: `LedgerEntry` details and monetary amounts. This
event is a *notification* ("a journal entry was posted, here's its id"),
not a data feed — a consumer that needs the actual debit/credit lines and
amounts is expected to call `GET /api/v1/journal-entries/{journalEntryId}`
using the id in the event. This keeps the REST API as the single source
of truth for financial data, rather than creating a second copy of it
(amounts, entry types) inside the event stream that would need its own
independent correctness guarantees and would double the surface area
that has to stay in sync if the ledger schema ever changes.

`eventVersion` is part of this event's public contract, not an
implementation detail, and is `1` for every event this phase produces. A
future change that alters the meaning of an existing field, removes a
field, or otherwise breaks a consumer parsing this schema **must**
introduce `eventVersion 2` rather than silently repurposing an existing
field's meaning; if the change is wire-incompatible, it should also get a
new topic (`ledger.journal-entry.posted.v2`, see below) so existing `.v1`
consumers are never broken out from under them. Purely additive,
backward-compatible fields may be added without a version bump, since
JSON consumers are expected to tolerate and ignore unknown fields.

### Topic naming

`ledger.journal-entry.posted.v1` (`LedgerKafkaTopics.JOURNAL_ENTRY_POSTED`)
follows a `<domain>.<entity>.<event-type>.<version>` convention. The
version suffix in the topic name is coarser than, and complements rather
than replaces, `eventVersion` in the payload: a wire-*incompatible*
schema change gets an entirely new topic (`.v2`), while additive,
backward-compatible changes stay on `.v1` and are distinguished (if
needed) by the payload's own `eventVersion` field.

### Message key

The Kafka message key is `journalEntryId.toString()`. Two reasons:

- **Partition ordering for a given entry.** Keying by `journalEntryId`
  guarantees every message concerning a given `JournalEntry` — this
  "posted" event today, and any future correction/reversal/amendment
  event for the same entry in a later phase — lands on the same
  partition, and Kafka only guarantees ordering *within* a partition.
  Consumers can therefore rely on messages about the same journal entry
  arriving in the order they were produced, even though this phase only
  ever produces one such message per entry.
- **Even load distribution.** `journalEntryId` is a randomly-generated
  UUID, so keys — and therefore partition load — are spread evenly.
  Keying on something coarser and lower-cardinality instead, like an
  account id, would concentrate a high-activity account's events onto a
  single partition and could throttle its effective throughput relative
  to per-entry keying.

### Producer configuration

`LedgerKafkaProducerConfig` defines a dedicated
`ProducerFactory<String, JournalEntryPostedMessage>` /
`KafkaTemplate<String, JournalEntryPostedMessage>` bean pair, not the
Spring Boot auto-configured `KafkaTemplate<Object, Object>` that
`spring.kafka.producer.*` in `application.yml` describes (that bean is
Phase 0 scaffold and remains present, but unused by ledger event
publishing). Every setting is explicit:

| Setting | Value | Why |
|---|---|---|
| `BOOTSTRAP_SERVERS_CONFIG` | `${spring.kafka.bootstrap-servers}` | Same property (and `KAFKA_BOOTSTRAP_SERVERS` env var) already wired for the rest of the app — one place to configure the cluster address. |
| `CLIENT_ID_CONFIG` | `double-entry-ledger-events-producer` | A distinct, descriptive client id so this producer is identifiable in broker-side logs/metrics/quotas, separate from any future producer or consumer. |
| `KEY_SERIALIZER_CLASS_CONFIG` | `StringSerializer` | The key is a plain string (`journalEntryId.toString()`); no richer serializer is needed. |
| `VALUE_SERIALIZER_CLASS_CONFIG` | `JsonSerializer` (Spring Kafka) | The payload is a structured object read by consumers outside this JVM; JSON is broadly interoperable and evolves compatibly. |
| `ACKS_CONFIG` | `all` | Waits for acknowledgment from every in-sync replica, not just the partition leader — the strongest durability guarantee Kafka offers, appropriate for a financial-domain event stream. (This environment's docker-compose Kafka has replication factor 1, so `acks=all` behaves like `acks=1` locally — the setting is what a real, replicated production cluster needs.) |
| `RETRIES_CONFIG` | `3` | A small number of client-level retries absorbs transient network blips or leader elections without any custom retry code. |
| `ENABLE_IDEMPOTENCE_CONFIG` | `true` | Pairs with retries: guarantees a retried send can't be written to the partition twice, so producer-level retries can never themselves cause a duplicate event on the broker. |
| `JsonSerializer.ADD_TYPE_INFO_HEADERS` | `false` | Spring's `JsonSerializer` otherwise stamps a `__TypeId__` header naming this JVM's Java class; external, non-Java consumers should depend only on the JSON body and `eventVersion`, not that implementation detail. |

### Failure handling

`LedgerEventPublisher` records publish failures with structured logging
that includes `journalEntryId`, `topic`, `exception`, and a `timestamp`
(see `logPublishFailure`), at `ERROR` level, with the full stack trace
attached via SLF4J's trailing-`Throwable` convention.

Nothing more robust than logging was built, deliberately. This is a
portfolio project without existing dead-letter/replay infrastructure, and
the two failure modes that would justify more are already handled by
cheaper mechanisms: transient network blips are absorbed by the
producer's own `retries`/`enable.idempotence` configuration above, and a
genuinely down broker would fail every publish attempt identically, so an
application-level retry loop would just delay the same log message rather
than change the outcome. Specifically **not built**: a scheduled
redelivery job, a dead-letter table, or an outer retry-with-backoff
wrapper around `kafkaTemplate.send()` — each would add real complexity
(persistence for pending events, a relay process, backoff/jitter tuning)
to guard against a failure mode (sustained broker unavailability) that,
for this project's actual reliability requirements, is adequately
answered by "log it, and the outbox migration path below if that ever
changes."

### Why not the Outbox Pattern (and how to get there later)

The **Outbox Pattern** solves the "dual write" problem: when a service
needs to both update its own database *and* notify another system (here,
Kafka) about the same business event, doing those as two independent
writes means there's no atomicity between them — the DB write can
succeed while the Kafka publish fails (or vice versa), with no single
transaction covering both. The Outbox Pattern closes that gap by writing
the event as a row in an `outbox` table, in the *same* database
transaction as the business data it describes (here, that would mean the
`JournalEntry`/`LedgerEntry`/`IdempotencyKey` inserts and an `outbox` row
insert all committing together, atomically, as one unit). A separate
relay process — either polling the outbox table or reading the
database's write-ahead log via CDC (e.g. Debezium) — then reads
committed outbox rows and publishes them to Kafka, retrying indefinitely
and marking rows as sent once Kafka confirms receipt. Because the outbox
row's existence is itself transactionally tied to the business data, an
event can never be "lost" the way an in-memory, post-commit publish
attempt theoretically could be if the app crashed between commit and
publish — production payment systems favor this pattern specifically for
that durability guarantee.

**This project intentionally uses `AFTER_COMMIT` publishing instead**, for
reasons specific to what this phase actually needs: it's dramatically
simpler to implement and reason about (no outbox table, no relay
process, no polling/CDC infrastructure to run and operate), and the
`@TransactionalEventListener(AFTER_COMMIT)` + producer-retries +
structured-logging combination above already fully satisfies this
phase's three correctness requirements — event-iff-committed, no failed
post from a publish failure, no duplicate on replay. The one gap
`AFTER_COMMIT` publishing has relative to an outbox — an event can be
silently lost if the JVM crashes in the narrow window between the
transaction committing and `LedgerEventPublisher` finishing its send — is
a real but narrow risk that isn't worth the added operational complexity
for a portfolio project with no downstream consumer yet to actually
depend on zero missed events.

**Migration path, if reliability requirements increase later**: introduce
an `outbox_events` table (`id`, `aggregate_id` = `journalEntryId`,
`event_type`, `payload` JSON, `created_at`, `published_at` nullable);
change `PostingService.postWithinTransaction` to insert a row into it
(via a repository call, still inside the same `@Transactional` method)
instead of — or in addition to, during a transition period — calling
`eventPublisher.publishEvent(...)`; and replace `LedgerEventPublisher`'s
`@TransactionalEventListener` with a scheduled poller (or a Debezium
connector reading the table's WAL changes) that reads unpublished outbox
rows, sends them to Kafka, and marks `published_at` on confirmed send —
retrying indefinitely on failure instead of just logging. The domain
event / Kafka event split already in place (`JournalEntryPostedEvent` vs.
`JournalEntryPostedMessage`) means `PostingService` would need no further
changes beyond the outbox insert — it already doesn't know or care how
"a journal entry was posted" gets turned into a Kafka message.

### Testing approach: `@EmbeddedKafka`, not Testcontainers

Testcontainers was evaluated and re-confirmed unusable in this
environment while building this phase: a throwaway
`KafkaContainer`-based smoke test was written and run, and it failed with
the same `BadRequestException` (Docker API negotiation) already
documented in `LedgerApplicationTests` for Postgres/Kafka Testcontainers
— this Docker Desktop installation still negotiates API v1.55, which
Testcontainers 1.20.3's bundled `docker-java` still rejects. That
throwaway test was deleted after confirming the failure; it was never
part of the committed test suite.

Rather than fall back to the shared, long-lived docker-compose Kafka
container the way Postgres integration tests do (the workaround adopted
in earlier phases, since no embedded-Postgres alternative is available),
Kafka event-publishing tests use **`@EmbeddedKafka`**, Spring Kafka's
in-process test broker. `spring-kafka-test` has been a declared
test-scope dependency since Phase 0 but was unused until this phase.
`@EmbeddedKafka` was chosen over continuing the docker-compose-container
pattern because, unlike with Postgres, a viable Docker-independent
alternative already existed in the dependency tree: it runs fully
in-process (no Docker involved at all, sidestepping the API-version
question entirely rather than depending on its outcome), starts fresh
per test class rather than sharing state with a long-lived container, and
needs no `docker compose up` to be running for `mvn test` to pass.

`LedgerEventPublisherTest` (`com.abel.ledger.kafka`) is a plain Mockito
unit test against `LedgerEventPublisher` with a mocked `KafkaTemplate` —
no broker at all — verifying the correct topic/key/message are sent, and
that a synchronous throw or an exceptionally-completed future from
`kafkaTemplate.send()` never propagates.

`LedgerEventPublishingIntegrationTest` (`@SpringBootTest` +
`@EmbeddedKafka`, real `PostingService` and a real `Consumer` polling the
embedded broker) covers the three correctness requirements end to end:

- `publishesEventAfterSuccessfulPost` — a successful post produces
  exactly one Kafka record, keyed by `journalEntryId`, whose JSON body
  matches the posted `JournalEntry`.
- `publishesNoEventWhenPostingFailsBecauseEntriesAreUnbalanced` /
  `publishesNoEventWhenPostingFailsBecauseOfCurrencyMismatch` — a
  rolled-back posting attempt produces zero records within a bounded
  poll window. This is the direct proof that the after-commit listener
  never fires for a transaction that didn't commit.
- `publishesExactlyOneEventAcrossAnIdempotentReplay` — posting the same
  request twice (same `idempotencyKey`, same payload) produces exactly
  one Kafka record, not two.

## Observability

Phase 6's job was to make the running application's behavior inspectable
from the outside — structured logs, meaningful metrics, accurate health
reporting — without changing any existing business logic.
`PostingService`, `BalanceService`, and `LedgerEventPublisher`'s core
logic have zero behavioral changes this phase; every file in
`com.abel.ledger.observability` is purely additive instrumentation (see
"Key design decisions" above for why AOP was used for two of those three
classes and direct instrumentation for the third).

### Structured logging

**Plain text by default, JSON via the `json-logs` profile.**
`logback-spring.xml` defines two `<springProfile>`-gated variants of the
same `CONSOLE` appender: the default (any profile except `json-logs`)
uses a human-readable pattern; `json-logs` swaps in
`net.logstash.logback.encoder.LogstashEncoder`. The split exists because
the two audiences want different things from the same information — a
developer running the app locally is visually scanning scrolling output
at a terminal, where a dense, grep-friendly text line is faster to read
than JSON; a production deployment's logs are consumed by a log
aggregator (ELK, Loki, CloudWatch Logs Insights, Datadog, ...) that wants
one JSON object per line with fields it can index and query on, where
human-readability of the raw stream doesn't matter. Defaulting to plain
text and opting into JSON (rather than the reverse) matches this
project's actual usage so far: every `docker compose`/`mvnw` invocation
in this repo's history has been local development, and a real deployment
is expected to explicitly set `SPRING_PROFILES_ACTIVE` to include
`json-logs` (see "How to Monitor This Service" below).

**One log statement, two renderings.** Every structured field in this
phase's log statements is passed via
`net.logstash.logback.argument.StructuredArguments.kv("key", value)` as
an SLF4J message argument, with a matching `{}` placeholder in the
message template (e.g. `log.info("Journal entry posted {} {} {}",
kv("journalEntryId", id), kv("idempotencyKey", key), kv("referenceId",
ref))`). `StructuredArguments.kv(...).toString()` renders as
`"key=value"`, which is what substitutes into the `{}` placeholder in
plain-text mode — so the same call site produces a readable
`journalEntryId=... idempotencyKey=... referenceId=...` line at a
terminal. Under `json-logs`, `LogstashEncoder` independently calls each
argument's `writeTo(JsonGenerator)` and adds `journalEntryId`,
`idempotencyKey`, and `referenceId` as top-level JSON fields — the exact
mechanism `PostingObservabilityIntegrationTest` exercises directly (see
"Testing" below) rather than parsing rendered text.

**No sensitive data in plaintext logs, deliberately.** No log statement
anywhere in this codebase logs a full account number
(`Account.accountNumber`) or a monetary amount
(`LedgerEntry.amount`/`PostingRequest`'s entry amounts). Only
`journalEntryId`, `idempotencyKey`, `referenceId`, `accountId` (a UUID,
not the human-assigned `accountNumber`), and exception details are
logged. This is a deliberate boundary, not an oversight: `journalEntryId`
and `accountId` are opaque, internally-generated UUIDs that are useless
to an attacker without database access and are exactly what's needed to
correlate a log line back to a specific record via the REST API or a
direct query — but an account number (a business-meaningful, often
externally-visible identifier) and a transaction amount are the kind of
data a financial system's logs should minimize exposure of, since logs
routinely have broader read access (log aggregators, on-call engineers,
retention policies) than the database itself. A consumer that needs the
actual amount is expected to call the REST API, which sits behind
whatever authorization this project's still-pending Phase 7 (security)
introduces — logs should not become a side channel that bypasses it.

**MDC and `correlationId`.** `CorrelationIdFilter` (see below) places the
correlation id in SLF4J's `MDC` under the key `correlationId` for the
duration of a request. Every log statement, from every class, therefore
gets `correlationId` "for free" — it's included in the plain-text pattern
via `%X{correlationId:-none}` and in JSON output automatically, since
`LogstashEncoder` includes all MDC entries as top-level fields by
default. No log statement needs to explicitly pass `correlationId`.

### Correlation id

`CorrelationIdFilter` (`@Order(Ordered.HIGHEST_PRECEDENCE)`, so it runs
before anything else, including the request-logging filter Spring Boot's
`ServerHttpObservationFilter` installs) does three things per request:
reads `X-Correlation-ID` from the incoming request if the client
supplied one; generates a UUIDv7 via `Uuid7Generator` if not; puts it in
MDC under `correlationId` for the lifetime of the request (cleared in a
`finally` block, so it can never leak into a later request handled on a
reused thread — `CorrelationIdFilterIntegrationTest` asserts this
directly); and echoes it back in the `X-Correlation-ID` response header
either way, so a client that didn't supply one can still capture it for
its own logs/support requests.

**Why UUIDv7, not `UUID.randomUUID()` (v4).** A UUIDv7 embeds a 48-bit
millisecond Unix timestamp in its first 48 bits, ahead of its random
bits (see `Uuid7Generator`'s javadoc for the exact bit layout). This
means UUIDv7 values sort — and therefore index — naturally by creation
time, which is a genuinely useful property for a correlation id
specifically: log storage and search tools that sort or partition by a
lexicographically/numerically sortable id benefit from this, and it
requires no library beyond what's already on the classpath — `java.util.UUID`
just has no v7 constructor, so `Uuid7Generator` hand-assembles the 128
bits per RFC 9562. `UUID.randomUUID()` (v4) has no such ordering
property; every value is independent, uniformly-random noise with
respect to when it was created.

**Known limitation: correlation id can be absent from Kafka
publish-failure logs.** `LedgerEventPublisher.onJournalEntryPosted` runs
synchronously on the original request thread (as part of the
`@TransactionalEventListener(AFTER_COMMIT)` callback chain — see "Event
Publishing"), so `correlationId` is present in MDC and appears in the
"Published ledger event to Kafka" / initial log context normally.
However, the actual outcome of `kafkaTemplate.send(...)` is determined
inside a `CompletableFuture.whenComplete(...)` callback, which — once the
send is genuinely asynchronous — can run on the Kafka producer's own I/O
thread (`kafka-producer-network-thread-...`) rather than the request
thread. MDC is thread-local, so a callback that fires on a different
thread simply won't see the request thread's `correlationId`. This is
documented in `LedgerEventPublisher`'s `logPublishFailure` javadoc rather
than "fixed," because fixing it would mean manually capturing and
re-installing MDC context across the async boundary — real, but
non-trivial, additional complexity that a portfolio project's Kafka
failure logging doesn't currently warrant (the `journalEntryId` and
`eventId` fields already in that log line are sufficient to find the
right context by other means).

### Metrics

All business metrics live under the `ledger.` namespace and use tags
(`outcome`, `failure_reason`) rather than a separate counter per outcome,
so a single Prometheus query like
`sum(rate(ledger_posting_requests_total[5m])) by (outcome)` answers "what
fraction of postings are failing right now" without needing to know the
metric names for every failure mode in advance.

| Metric | Type | Tags | Operational question it answers |
|---|---|---|---|
| `ledger.posting.requests` | Counter | `outcome=success\|failure`, `failure_reason=unbalanced\|currency_mismatch\|idempotency_conflict\|account_not_found\|invalid_request\|unknown` (failure only) | How much posting traffic is succeeding vs. failing, and *why* it's failing, broken down by cause — is a spike in errors client-side bad requests (`unbalanced`/`invalid_request`), a data problem (`account_not_found`), or contention (`idempotency_conflict`)? |
| `ledger.posting.duration` | Timer | `outcome=success\|failure` | Posting latency distribution (count/sum/max, and percentiles if a percentile histogram is later enabled) — is the posting engine slow, and does that correlate with success or failure? |
| `ledger.balance.requests` | Counter | `outcome=success\|failure` | How much balance-read traffic there is and how often it fails (almost always `AccountNotFoundException` — a client querying a bad id). |
| `ledger.balance.duration` | Timer | `outcome=success\|failure` | Balance-read latency — since this is a live `SUM` aggregation query with no caching (see "No stored balance column"), this is the metric to watch as `ledger_entries` grows. |
| `ledger.kafka.publish.requests` | Counter | `outcome=success\|failure` | Is the ledger's event stream actually keeping up — are events reaching Kafka, or silently failing (see "Failure Handling" in "Event Publishing")? |
| `ledger.kafka.publish.duration` | Timer | `outcome=success\|failure` | Kafka publish latency — since `KafkaTemplate.send()` is non-blocking, this measures the producer's own send-to-ack time, not request latency. |
| `ledger.idempotency.conflicts` | Counter | none | How often a client reuses an `idempotencyKey` with a genuinely different payload (`IdempotencyKeyConflictException`) — a signal of a likely client-side bug (reusing a key across unrelated requests), distinct from the expected, harmless case of retrying the *same* payload. |
| `ledger.concurrency.recoveries` | Counter | none | How often the concurrency-race recovery path (see "Concurrency") is actually exercised in practice — near-zero in normal traffic, rising under genuine concurrent retry storms; a sustained high rate would suggest clients are retrying far more aggressively than expected. |

`ledger.posting.requests`/`ledger.posting.duration` and
`ledger.concurrency.recoveries` are recorded by
`PostingObservabilityAspect`; `ledger.idempotency.conflicts` is recorded
in the same `@Around` advice around `post()`, since
`IdempotencyKeyConflictException` can originate from either the
sequential-replay path or the concurrency-recovery path and both are
caught by that one advice regardless of which internal method raised it.
`ledger.balance.*` is recorded by `BalanceObservabilityAspect`.
`ledger.kafka.publish.*` is recorded directly inside
`LedgerEventPublisher` (see "Key design decisions" above for why).

Beyond these custom metrics, Spring Boot Actuator's Micrometer
integration automatically provides (no code required, since
`spring-boot-starter-actuator` + `micrometer-registry-prometheus` are on
the classpath and `management.endpoints.web.exposure.include` lists
`metrics` and `prometheus`):

- **HTTP request metrics** (`http.server.requests`, exposed to
  Prometheus as `http_server_requests_seconds_*`) — latency and count
  tagged by `uri`, `method`, `status`, and `outcome`, covering every
  endpoint including the ones this phase didn't touch.
- **JVM metrics** (`jvm.memory.*`, `jvm.gc.*`, `jvm.threads.*`) — heap/
  non-heap memory by pool, GC pause time, live thread count.
- **DataSource/HikariCP metrics** (`hikaricp.connections.*`) — active/
  idle/pending connection counts against the pool sized in "Concurrency"
  above — the metric to watch if that pool sizing ever needs revisiting.

### Prometheus

`/actuator/prometheus` is exposed via `micrometer-registry-prometheus`
(added this phase) and `management.endpoints.web.exposure.include:
health,info,metrics,prometheus` in `application.yml`. Every metric above
— custom and Boot-provided alike — appears in that one scrape response,
in standard Prometheus text exposition format (dots become underscores,
`_total`/`_seconds` suffixes are added per Prometheus naming convention,
e.g. `ledger.posting.requests` → `ledger_posting_requests_total`). See
"How to Monitor This Service" below for a real captured example and a
sample scrape config.

`management.metrics.tags.application: ${spring.application.name}` tags
every metric (custom and built-in) with `application="double-entry-ledger"`,
so metrics from this service are distinguishable from any other service's
in a shared Prometheus instance without needing a separate `job` label
convention per team.

### Health checks

`/actuator/health` (present since Phase 0/3, previously reporting only
`db` implicitly via Boot's auto-configured `DataSourceHealthIndicator`)
now reports `db`, `kafka`, `diskSpace`, and `ping` as separate components,
each with its own `status` and `details`, using Actuator's standard
composite-health-indicator conventions — `show-details: always` (already
set since Phase 3) means the full breakdown, not just the aggregate
status, is always visible:

```json
{
  "status": "UP",
  "components": {
    "db": { "status": "UP", "details": { "database": "PostgreSQL", "validationQuery": "isValid()" } },
    "kafka": { "status": "UP", "details": { "bootstrapServers": ["localhost:9092"], "clusterId": "...", "nodeCount": 1 } },
    "diskSpace": { "status": "UP", "details": { "...": "..." } },
    "ping": { "status": "UP" }
  }
}
```

**`KafkaHealthIndicator` (new this phase)** calls
`AdminClient.describeCluster()` against the same `KafkaAdmin` bean the
rest of the app's Kafka configuration is built from, bounded by a 2
-second timeout so an unreachable broker reports DOWN quickly rather than
hanging the endpoint. It replaces Spring Boot's own auto-configured Kafka
health indicator (kept off via `management.health.kafka.enabled: false`,
originally disabled in Phase 3 when no Kafka publishing existed at all —
now kept off specifically to avoid two indicators both claiming the
`kafka` component name, not because Kafka health reporting is unwanted).

**No custom database health indicator was written.** Spring Boot's
auto-configured `DataSourceHealthIndicator` already does exactly what
this phase needs — a real validation query (`Connection.isValid()`)
against the actual `DataSource` bean, reporting UP/DOWN with database
product details — and has been present, correct, and exercised by every
integration test in this project since Phase 1. Writing a second,
custom database indicator would only duplicate it with no behavioral
difference; the honest, non-redundant choice was to document that
decision rather than write code whose only purpose would be to exist.

**Deliberate tradeoff: a down Kafka broker makes the overall
`/actuator/health` status DOWN, even though posting still works without
Kafka** (per Phase 5's explicit design — a publish failure never fails a
post). This is standard Actuator composite-health behavior (overall
status = worst of all components) and was kept as-is rather than
building a custom health group to separate "can this instance serve
traffic" from "is every dependency healthy," because this phase didn't
ask for Kubernetes-probe-specific behavior and building that grouping
without a concrete consumer for it would be speculative complexity. The
practical implication for an operator: a DOWN `kafka` component means
"ledger events aren't being published," not "the ledger is broken" —
check `components.db.status` specifically to know whether posting itself
is affected. If this project ever adds liveness/readiness-probe-specific
behavior, Spring Boot's `management.endpoint.health.group.*` 
configuration (splitting `db` into a "readiness" group that excludes
`kafka`) is the standard mechanism to reach for.

### Testing

**Logging Verification** uses a Logback `ch.qos.logback.core.read.ListAppender`
(a real, public, purpose-built test utility bundled in `logback-core` —
no custom hand-rolled appender needed), attached directly to the logger
under test (e.g. `com.abel.ledger.observability.posting`) in `@BeforeEach`
and detached in `@AfterEach`. Rather than asserting on the *rendered*
log line (brittle: sensitive to message wording, pattern changes, or
plain-text-vs-JSON mode), `PostingObservabilityIntegrationTest` walks
each captured `ILoggingEvent`'s `getArgumentArray()`, filters for
`net.logstash.logback.argument.StructuredArgument` instances, and calls
each one's own `writeTo(JsonGenerator)` — the exact method
`LogstashEncoder` itself calls in production — to render them into a
field map, then asserts on that map directly (`containsEntry("journalEntryId", ...)`).
This tests the actual structured-data contract a real JSON log line would
carry, independent of message wording.

**Correlation id propagation** is verified via `MockMvc` in
`CorrelationIdFilterIntegrationTest`: a supplied `X-Correlation-ID` is
echoed back unchanged; an omitted one produces a generated, valid UUIDv7
in the response header (`UUID.fromString(...).version() == 7`); and MDC
is confirmed empty immediately after the request completes.

**Metrics** are verified against the real, Spring-managed `MeterRegistry`
bean (`PostingObservabilityIntegrationTest`, `BalanceObservabilityIntegrationTest`,
`LedgerEventPublisherTest`) using a capture-before/act/assert-delta-of-1
pattern rather than asserting an absolute count — necessary because
`MeterRegistry` is a singleton shared across the whole test suite (many
other test classes call `postingService.post(...)` too), so an absolute
count would be flaky depending on test execution order.
`meterRegistry.counter(name, tags...)` / `.timer(name, tags...)` (a
register-or-get lookup — the same idiom the aspects themselves use) is
used to capture the Counter/Timer reference *before* the operation,
specifically to avoid `MeterRegistry.get(name).tags(...).counter()`,
which throws `MeterNotFoundException` if that exact tag combination has
never been recorded yet (a real failure mode hit and fixed while writing
these tests, for the `failure_reason` tags that don't exist in a fresh
registry until the first failure of each kind occurs).

**Health checks** are verified two ways: `HealthEndpointIntegrationTest`
(MockMvc, real docker-compose Postgres + Kafka, both healthy) proves the
end-to-end wiring — `components.db.status`/`components.kafka.status`
both present and `UP`; `KafkaHealthIndicatorTest` exercises
`KafkaHealthIndicator` directly, in isolation, against both the real
broker (UP) and a deliberately unreachable address (`localhost:1`, a
reserved port nothing listens on — DOWN, with an `error` detail),
without needing to actually stop the shared docker-compose Kafka
container the rest of the suite depends on.

## Authentication and Authorization

Phase 7's job was to require an authenticated, sufficiently-privileged
caller for the two write-shaped operations (posting a journal entry;
account creation, if a create-account endpoint is ever added) while
keeping every read endpoint open to any authenticated caller — without
touching `PostingService`, `BalanceService`, `LedgerEventPublisher`, or
any controller's business logic. Every file this phase added or touched
lives in `com.abel.ledger.security`, `com.abel.ledger.domain.user`, a new
`AuthController`, and the `SecurityFilterChain`'s `authorizeHttpRequests`
rules — nothing in the posting/balance/event-publishing path changed.

### JWT design

- **Claims**: `sub` (username), `role` (`ADMIN` or `VIEWER`), `iat`, `exp`.
  Nothing else — no permissions list, no account-scoped grants. A request's
  authorization decision only ever needs to know who the caller is and
  which of the two roles they hold.
- **Algorithm**: HS256 (symmetric), via `jjwt` (`jjwt-api`/`jjwt-impl`/`jjwt-jackson`).
  A single shared secret (`jwt.secret`) signs and verifies every token;
  there's exactly one service issuing and consuming its own tokens, so
  there's no case for asymmetric signing (RS256) here.
- **Key derivation**: the signing key is `SHA-256(jwt.secret)`, not
  `jwt.secret`'s raw bytes. HS256 requires a key of at least 256 bits;
  the default `change-me-for-local-dev` (and most human-typed secrets)
  is shorter than that, so using it directly would make key construction
  fail at startup. Hashing always yields exactly 32 bytes regardless of
  the configured secret's length while still deriving deterministically
  from it — two instances configured with the same `jwt.secret` verify
  each other's tokens. See `JwtService`'s javadoc for the exact reasoning.
- **Expiry**: `jwt.expiration` (`PT1H` by default, an ISO-8601 duration
  bound directly to `java.time.Duration` by Spring Boot's relaxed
  binding). A `JwtAuthenticationFilter` on every request either
  authenticates the caller from a still-valid token's claims, or — for a
  missing, malformed, wrong-signature, or expired token — leaves the
  request unauthenticated and lets `authorizeHttpRequests` decide what
  happens next (see "Authorization enforcement" below). No refresh flow
  exists yet; see "Deliberately not built" below.
- **Transport**: `Authorization: Bearer <token>` only. No cookies —
  nothing here needs cookie-based session semantics, and using a header
  keeps CSRF entirely out of scope (see "Statelessness" below).

### Role model

Two roles, no more:

- **ADMIN** — everything VIEWER can do, plus `POST /api/v1/journal-entries`
  (and any future account-creation endpoint).
- **VIEWER** — read-only: `GET /api/v1/accounts/{id}/balance`,
  `GET /api/v1/accounts/{id}/ledger`, `GET /api/v1/journal-entries/{id}`.

There is no per-account or per-transaction-type permission — a VIEWER can
read every account's balance and ledger, not just some. That's a
deliberate simplification (see "Deliberately not built" below), not an
oversight.

### Authorization enforcement

All of it lives in `SecurityConfig#securityFilterChain`'s
`authorizeHttpRequests` block — a single, readable list of URL/method
rules, not scattered `@PreAuthorize` annotations across controllers:

- `/`, `/api/v1/auth/login`, `/actuator/health`, `/swagger-ui.html`,
  `/swagger-ui/**`, `/v3/api-docs`, `/v3/api-docs/**` — public.
- `POST /api/v1/journal-entries`, `POST /api/v1/accounts/**` — `ADMIN` only.
  (`/api/v1/accounts/**` is included pre-emptively: no account-creation
  endpoint exists yet — `AccountController` is read-only today — but the
  rule is already in place so one can be added later without a second
  security review.)
- Everything else (every other endpoint that exists today: the balance,
  ledger, and single-journal-entry GETs) — any authenticated user,
  `ADMIN` or `VIEWER`.

`JwtAuthenticationFilter` (registered via `.addFilterBefore(...,
UsernamePasswordAuthenticationFilter.class)`) is the only place a token
is parsed. On a valid token it populates `SecurityContextHolder` with the
username and a `ROLE_<role>` authority taken straight from the token's
claims — no per-request database lookup. On any parse/verify failure it
does nothing and lets the request continue as anonymous; whether that
matters is entirely up to `authorizeHttpRequests` and the two handlers
below.

**401 vs. 403, and why they're distinct classes.** `RestAuthenticationEntryPoint`
handles "not authenticated at all" (missing header, malformed token,
wrong signature, expired token — `JwtAuthenticationFilter` collapses all
of these into "anonymous," so they all land here) and writes 401 in the
same `ErrorResponse` shape the rest of the API uses.
`RestAccessDeniedHandler` handles "authenticated, but the wrong role" —
a VIEWER token hitting `POST /api/v1/journal-entries` — and writes 403.
Reusing `GlobalExceptionHandler`'s `ErrorResponse` record (rather than
Spring Security's default HTML/plain-text error output) keeps every
error response in this API — business, validation, or auth — the same
shape.

### Password storage and login

`User` (`domain/user`) stores only a BCrypt `passwordHash`, never a
plaintext password, via a singleton `BCryptPasswordEncoder` bean used
everywhere a password is checked or stored. `CustomUserDetailsService`
loads a `User` by username and adapts it to Spring Security's
`UserDetailsService` contract; `POST /api/v1/auth/login` (`AuthController`)
hands the submitted credentials to the standard `AuthenticationManager` /
`DaoAuthenticationProvider` pipeline rather than comparing hashes by
hand, so unknown-username and wrong-password both fail the same way
(`AuthenticationException` → 401) without revealing which case occurred.

### Statelessness

`SessionCreationPolicy.STATELESS` and CSRF disabled. There's no session
to fixate or hijack, and no cookie for a CSRF token to protect — the only
credential a client presents is a bearer token it must explicitly attach
itself, which is exactly the CSRF mitigation a token-in-header scheme
provides by construction.

### `@EnableWebSecurity` is explicit, not implicit

`SecurityConfig` declares `@EnableWebSecurity` directly rather than
relying on Spring Boot's own auto-configuration
(`WebSecurityEnablerConfiguration`), because that auto-import is gated
behind `@ConditionalOnWebApplication(type = SERVLET)`. This project
already has tests that boot a non-web application context
(`PostingServiceConcurrencyTest` uses `@SpringBootTest(webEnvironment =
NONE)`) — component scanning still finds and instantiates every
`@RestController` there, including the new `AuthController`, which needs
an `AuthenticationManager` bean regardless of whether a servlet container
exists. Declaring `@EnableWebSecurity` explicitly makes that bean (and
`HttpSecurity`) available in any context type, not just a servlet one.

### Correlation id filter interaction

`CorrelationIdFilter` needed no changes. It's registered as a plain
servlet filter at `@Order(Ordered.HIGHEST_PRECEDENCE)`
(`Integer.MIN_VALUE`), which places it ahead of Spring Security's own
filter chain (Boot registers that at `SecurityProperties.DEFAULT_FILTER_ORDER`,
`-100`) regardless of how authentication turns out. It runs — and
establishes `correlationId` in MDC and the response header — before
`JwtAuthenticationFilter` ever executes, for both a request that
ultimately succeeds and one `RestAuthenticationEntryPoint`/`RestAccessDeniedHandler`
rejects. No log statement anywhere in the new security code logs a JWT,
a password, or a password hash — `JwtAuthenticationFilter`'s debug log on
a rejected token records only the exception's class name.

### Deliberately not built

- **Refresh tokens.** Out of scope for this phase, per its instructions.
  A 1-hour-expiry access token with no refresh path means a session
  requires re-authenticating hourly — acceptable for a portfolio
  demonstration, not for a real deployment.
- **Password reset.** No self-service or admin-initiated reset flow.
  The two seeded demo users are the only accounts; there's no forgot
  password / email verification infrastructure to build a reset flow on
  top of.
- **Fine-grained, per-account permissions.** VIEWER can read every
  account's balance and ledger, not a scoped subset. A real multi-tenant
  ledger would need per-account or per-customer authorization; that's a
  materially bigger design problem (how is an account-to-caller mapping
  established and maintained?) deferred well past this phase's scope.
- **Rate limiting on login.** `POST /api/v1/auth/login` has no
  brute-force protection (lockout, exponential backoff, CAPTCHA). A real
  deployment would want this; a two-user demo credential set doesn't
  meaningfully benefit from it.

## How to Monitor This Service

### Actuator endpoints

With `management.endpoints.web.exposure.include: health,info,metrics,prometheus`
(`application.yml`), this service exposes:

| Endpoint | Purpose |
|---|---|
| `GET /actuator/health` | Overall + per-component (`db`, `kafka`, `diskSpace`, `ping`) status. `show-details: always`, so the full breakdown is always visible — see "Health checks" above. |
| `GET /actuator/info` | Application metadata (currently minimal — no custom `InfoContributor` has been added). |
| `GET /actuator/metrics` | Lists all available Micrometer metric names. |
| `GET /actuator/metrics/{name}` | A single metric's measurements and available tags, e.g. `GET /actuator/metrics/ledger.posting.requests`. |
| `GET /actuator/prometheus` | Every metric (custom `ledger.*` and Boot-provided JVM/HTTP/datasource) in Prometheus text exposition format. |

### Example Prometheus scrape configuration

```yaml
scrape_configs:
  - job_name: double-entry-ledger
    metrics_path: /actuator/prometheus
    scrape_interval: 15s
    static_configs:
      - targets: ["localhost:8080"]
```

### Example log output

A successful posting, captured from a real running instance of this
exact code (`POST /api/v1/journal-entries` with header
`X-Correlation-ID: json-verify-1`):

**Plain text (default):**

```
2026-07-08T20:33:00.940-07:00 INFO  [http-nio-8081-exec-2] c.abel.ledger.observability.posting [correlationId=json-verify-1] - Journal entry posted journalEntryId=bdf3e0ec-f2b3-47eb-98de-04f0aaead87f idempotencyKey=idem-d9a68777-5a92-43f3-9607-1a6f96688c2f referenceId=REF-462a5fed-ca08-41df-ad3a-d97847506ba3
```

**JSON (`--spring.profiles.active=json-logs`, or
`SPRING_PROFILES_ACTIVE=json-logs` as an env var):**

```json
{
  "@timestamp": "2026-07-08T20:46:22.327411-07:00",
  "@version": "1",
  "message": "Journal entry posted journalEntryId=588cf062-b186-4eca-8b16-f87f810569ba idempotencyKey=json-idem-1 referenceId=JSON-REF-1",
  "logger_name": "com.abel.ledger.observability.posting",
  "thread_name": "http-nio-8081-exec-2",
  "level": "INFO",
  "level_value": 20000,
  "correlationId": "json-verify-1",
  "journalEntryId": "588cf062-b186-4eca-8b16-f87f810569ba",
  "idempotencyKey": "json-idem-1",
  "referenceId": "JSON-REF-1",
  "service": "double-entry-ledger"
}
```

Note that in JSON mode, `journalEntryId`/`idempotencyKey`/`referenceId`
appear both inside the rendered `message` text (harmless — the
`StructuredArguments.kv(...)` call renders "key=value" either way) *and*
as independent, queryable top-level fields — the dual-rendering behavior
described in "Structured logging" above.

## Production Readiness

What Phases 6-8 actually built toward running this in front of real
traffic, and — just as important for a portfolio piece to be honest
about — what's still missing before it should.

### What's built

- **Observability** (Phase 6): structured logs (plain text locally, JSON
  in production via the `json-logs` profile), a correlation id threaded
  through every log line and returned to the caller, `ledger.*` business
  metrics plus Boot's standard HTTP/JVM/datasource metrics on
  `/actuator/prometheus`, and health checks that distinguish database
  from Kafka availability.
- **Security** (Phase 7): stateless JWT authentication, two-role
  authorization enforced at the security-filter layer, BCrypt password
  storage, no plaintext credentials or tokens in any log line.
- **CI/CD** (Phase 8): every push and pull request to `main` runs the
  full test suite against real Postgres and Kafka (the same
  docker-compose stack used locally, not a mocked substitute) and an
  OWASP Dependency-Check vulnerability scan; every push to `main`
  additionally builds the Docker image and scans it with Trivy, failing
  on HIGH/CRITICAL findings. See `.github/workflows/ci.yml`.
- **Container image** (Phase 8): multi-stage build with a JRE-only (no
  build tooling) runtime image, a non-root user, a `HEALTHCHECK` against
  `/actuator/health`, and OCI metadata labels.
- **Load testing** (Phase 8): a repeatable `hey`-based script and
  documented, actually-measured throughput/latency numbers — see
  `docs/load-testing.md` — distinct from Phase 4's
  `PostingServiceConcurrencyTest`, which proves correctness under
  concurrency rather than measuring performance.

### What's deliberately still out of scope

This is a portfolio project demonstrating specific engineering
decisions, not a system being handed a production traffic allocation.
Left out on purpose, not by oversight:

- **Secrets management.** `jwt.secret` and database credentials are
  environment variables with local-dev defaults (see
  `application.yml`); nothing here integrates a real secrets manager
  (Vault, AWS Secrets Manager, etc.). A real deployment would need one —
  environment variables on a shared host or in a process listing are not
  an acceptable place for a JWT signing key or a database password.
- **Database backup and recovery.** No backup schedule, point-in-time
  recovery, or tested restore procedure exists for the Postgres data.
  For a system whose entire value proposition is an accurate,
  never-lose-a-transaction ledger, this would be one of the first things
  a real deployment needs — and one of the areas where "portfolio scope"
  and "production scope" diverge the most.
- **Multi-region / high availability.** Single Postgres instance, single
  Kafka broker (KRaft mode, one node), no read replicas, no failover.
  Fine for demonstrating the application's own correctness and
  concurrency properties; nowhere near what a financial system's
  availability requirements would actually demand.
- **Container registry and deployment pipeline.** CI builds and scans
  the Docker image but never pushes it anywhere — there's no registry
  configured and no deployment target (Kubernetes manifests, ECS task
  definitions, etc.). See `.github/workflows/ci.yml`'s comment on why:
  no registry credentials are wired up for this repository, deliberately.
- **Rate limiting and abuse protection**, beyond what's already noted in
  "Authentication and Authorization" (no login rate limiting). No
  request throttling, no WAF, no DDoS protection at any layer.
- **Refresh tokens, password reset, per-account permissions.** Already
  listed in "Authentication and Authorization" above; repeated here only
  to keep this the single place that answers "is this production-ready,"
  honestly.

## Roadmap

- **Phase 0** (done): runnable scaffold, health endpoint, infra wiring.
- **Phase 1** (done): accounts, immutable ledger entries, base schema.
- **Phase 2** (done): balanced/idempotent posting engine, live balance
  derivation, service-layer validation.
- **Phase 3** (done): versioned REST API (`/api/v1`) over the existing
  posting/balance/query engine, centralized exception-to-HTTP mapping,
  OpenAPI/Swagger documentation, paginated ledger history.
- **Phase 4** (done): proved posting/balance-reading correctness
  under real concurrent access; closed the one real race (concurrent
  `idempotencyKey` reuse) with a catch-and-replay recovery on top of the
  pre-existing unique constraint; documented why no elevated isolation or
  explicit locking is needed for this append-only, no-stored-balance
  schema; added `PostingServiceConcurrencyTest` and the manual
  `PostingServiceStressCheck`.
- **Phase 5** (done): publish a `ledger.journal-entry.posted.v1`
  Kafka event whenever a `JournalEntry` commits, via a domain event
  (`JournalEntryPostedEvent`) raised by `PostingService` and converted to
  a Kafka message by a separate `@TransactionalEventListener(AFTER_COMMIT)`
  (`LedgerEventPublisher`); no consumer yet, publishing only. Documented
  the Outbox Pattern as the future migration path if reliability
  requirements increase.
- **Phase 6** (done): structured logging (plain text by default,
  JSON via the `json-logs` profile, both from the same log statements),
  a `CorrelationIdFilter` propagating `X-Correlation-ID` through MDC,
  `ledger.*` business metrics alongside Boot's automatic HTTP/JVM/
  datasource metrics, `/actuator/prometheus`, and a custom `kafka` health
  indicator distinguishing database and Kafka availability — all added
  via AOP or purely-additive edits, with zero behavioral changes to
  `PostingService`/`BalanceService`/`LedgerEventPublisher`.
- **Phase 7** (done): stateless JWT authentication
  (`POST /api/v1/auth/login`, HS256, `sub`/`role`/`iat`/`exp` claims) and
  two-role (`ADMIN`/`VIEWER`) authorization via a Spring Boot 3
  `SecurityFilterChain` — posting journal entries requires `ADMIN`,
  every read endpoint requires any authenticated user. Enforced entirely
  at the security-filter layer; `PostingService`, `BalanceService`, and
  `LedgerEventPublisher` have no dependency on Spring Security. See
  "Authentication and Authorization" above.
- **Phase 8** (this phase, final): GitHub Actions CI/CD (test job against
  real Postgres/Kafka via docker-compose, OWASP Dependency-Check, a Docker
  build-and-Trivy-scan job), an optimized multi-stage Dockerfile
  (BuildKit cache mounts, non-root user, `HEALTHCHECK`, OCI labels), a
  `hey`-based load test measuring throughput/latency (distinct from Phase
  4's correctness-under-concurrency work), and documentation polish. See
  "Production Readiness" below. This is the last planned phase for this
  project.
