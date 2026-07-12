# double-entry-ledger

A double-entry bookkeeping service: the kind of core transaction ledger
used by banks and payment processors, built to demonstrate the
engineering practices that matter for financial infrastructure —
correctness under concurrency, idempotent writes, an immutable audit
trail, and defense-in-depth security — rather than to ship a specific
product feature.

Every transaction is a balanced journal entry (debits == credits,
enforced server-side). Ledger entries are append-only and immutable —
nothing is ever updated or deleted. Account balances are never stored;
they're always derived live by summing ledger entries, so a balance can
never drift out of sync with the entries that back it.

## Tech Stack

Java 21 · Spring Boot 3 · PostgreSQL · Apache Kafka (KRaft) · Flyway ·
Spring Security (JWT) · Docker · GitHub Actions

## Architecture

```
                 ┌──────────────┐
  HTTP  ──────▶  │  Spring MVC  │  JWT auth + role-based
 (REST)          │  Controllers │  authorization at this layer only
                 └──────┬───────┘
                        │
                 ┌──────▼───────┐
                 │   Services   │  PostingService / BalanceService /
                 │              │  LedgerQueryService — no framework
                 └──────┬───────┘  coupling to security or web concerns
                        │
              ┌─────────┴─────────┐
              ▼                   ▼
       ┌─────────────┐     ┌─────────────┐
       │  PostgreSQL │     │    Kafka     │  after-commit event publish
       │ (append-only│     │ (journal-    │  (@TransactionalEventListener,
       │   ledger)   │     │  entry-      │   AFTER_COMMIT — see below)
       │             │     │  posted.v1)  │
       └─────────────┘     └─────────────┘
```

Full design rationale — schema, concurrency correctness, event
publishing, observability, security — lives in
**[docs/architecture.md](docs/architecture.md)**. This README stays
short on purpose; that file is the detailed reference.

## Running it

```bash
docker compose up -d postgres kafka
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

or run the whole stack, including the app, in containers:

```bash
docker compose up --build
```

Verify it's up:

```bash
curl http://localhost:8080/actuator/health
# {"status":"UP", ...}
```

Interactive API docs: **http://localhost:8080/swagger-ui.html**

## Trying the API

Posting journal entries requires an `ADMIN` JWT; reading balances and
ledger history requires any authenticated user (`ADMIN` or `VIEWER`).

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username": "admin", "password": "admin123"}' | jq -r .token)

curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/api/v1/accounts/{id}/balance
```

**Demo credentials** (seeded by a Flyway migration — not for real use):

| Username | Password    | Role   |
|----------|-------------|--------|
| `admin`  | `admin123`  | ADMIN  |
| `viewer` | `viewer123` | VIEWER |

More worked examples (posting a transaction, pagination, error shapes):
**[docs/api-examples.md](docs/api-examples.md)**.

## Running tests

```bash
docker compose up -d postgres kafka
./mvnw verify
```

## Load testing

```bash
brew install hey
./scripts/load-test.sh
```

Documented, actually-measured results: **[docs/load-testing.md](docs/load-testing.md)**.

## CI/CD

Every push and pull request to `main` runs the full test suite against
real Postgres and Kafka, plus an OWASP dependency vulnerability scan;
every push to `main` also builds the Docker image and scans it with
Trivy. See `.github/workflows/ci.yml`.

## Engineering Highlights

A few decisions worth a closer look — each has a full write-up linked
below:

- **[Derived balances, never stored](docs/architecture.md#key-design-decisions).**
  There's no `balance` column anywhere in the schema. Every balance is
  computed live from the `ledger_entries` table via a native `SUM/CASE`
  query, so it's structurally impossible for a stored balance to drift
  from the entries that back it — the usual failure mode in ledgers that
  cache a running total.
- **[Idempotency as a first-class, separate concept from the business reference](docs/architecture.md#key-design-decisions).**
  `idempotencyKey` (retry safety) and `reference_id` (the caller's
  business identifier, e.g. an invoice number) are deliberately two
  different columns in two different tables, not one field serving
  double duty — conflating them would make it impossible to later reuse
  a business reference across a legitimate correction/reversal without
  breaking retry semantics.
- **[Concurrency correctness, proved rather than assumed](docs/architecture.md#concurrency).**
  `PostingServiceConcurrencyTest` drives real concurrent traffic —
  including a simulated retry storm where many threads submit the same
  `idempotencyKey` simultaneously — against the actual service and
  database, and asserts balances are never torn or negative and no
  duplicate journal entries are created. The one genuine race (concurrent
  first-use of an `idempotencyKey`) is closed with a catch-and-replay
  recovery on top of a database unique constraint, not application-level
  locking.
- **[After-commit Kafka publishing](docs/architecture.md#event-publishing).**
  Journal-entry-posted events publish to Kafka via
  `@TransactionalEventListener(phase = AFTER_COMMIT)` — a consumer can
  never observe an event for a transaction that isn't actually durable
  yet, because the event fires only after the database transaction
  commits, not when the domain event is raised.
- **[The Outbox Pattern, considered and deliberately deferred](docs/architecture.md#why-not-the-outbox-pattern-and-how-to-get-there-later).**
  The after-commit listener can still fail to publish after a successful
  commit (a real, documented gap). The Outbox Pattern is the standard
  fix — write the event to a table in the same transaction, publish from
  that table separately — and `docs/architecture.md` lays out exactly
  what migrating to it would look like, deferred because this project's
  current failure-handling (logged, alertable delivery failures) is
  sufficient for its actual scope.

## What's not built, on purpose

Secrets management, database backup/recovery, multi-region deployment,
and a few other real-production concerns are explicitly out of scope —
see **[docs/architecture.md, "Production Readiness"](docs/architecture.md#production-readiness)**
for the honest list and why.
