# Double Entry Ledger

![Java](https://img.shields.io/badge/Java-21-orange)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3-brightgreen)
![Build](https://img.shields.io/badge/build-Maven-blue)
![CI](https://img.shields.io/badge/CI-GitHub%20Actions-blue)
![License](https://img.shields.io/badge/license-MIT-lightgrey)

A backend double entry ledger modeled after the bookkeeping infrastructure used by banks and payment processors.
<!-- Screenshot placeholder: add a screenshot of Swagger UI here, e.g. docs/images/swagger.png -->

<!-- ![Swagger UI](docs/images/swagger.png) -->

Every transaction is recorded as a balanced pair of debit and credit entries. Balances are never stored. They're always derived from transaction history, so there's no number sitting in the database that can silently drift from reality.

The system is tested under concurrent load, publishes Kafka events only after successful database commits, and includes JWT authentication, structured logging, and observability.

Once running locally, Swagger UI is available at `http://localhost:8080/swagger-ui.html`. See [Running Locally](#running-locally) below for setup instructions and demo credentials.

## Why I built it this way

I wanted a project I could actually defend line by line in an interview, not just something that runs. I chose the double entry bookkeeping model specifically because getting it right forces you to deal with real engineering problems: atomic transactions, idempotency, concurrency correctness, and event driven architecture.

## API Overview

* Post balanced journal entries
* Retrieve a live, derived account balance
* View paginated account ledger history
* Look up a specific journal entry
* Authenticate with JWT and enforce role based access (ADMIN and VIEWER)

## Tech Stack

| Category      | Technologies              |
| ------------- | ------------------------- |
| Language      | Java 21                   |
| Framework     | Spring Boot 3             |
| Database      | PostgreSQL, Flyway        |
| Messaging     | Apache Kafka (KRaft mode) |
| Security      | Spring Security, JWT      |
| Observability | Micrometer, Prometheus    |
| DevOps        | Docker, GitHub Actions    |

## Architecture

## Engineering Highlights

The project focuses on correctness over feature count. A few implementation choices I'm particularly proud of:

* Immutable, append only ledger. No update or delete path exists for a posted transaction.
* Derived balances. No stored balance column anywhere in the schema.
* Database backed idempotency. A retried request with the same key never creates a duplicate.
* Kafka events published only after the database transaction commits.
* Concurrency tested with 50+ simultaneous requests against the same account, run multiple times to catch flakiness.
* Stress tested at 2,000 concurrent requests with no failures during testing.
* JWT authentication with role based access control (ADMIN and VIEWER).
* Correlation IDs on every request, traceable through structured logs.
* GitHub Actions CI pipeline that runs integration tests, builds the Docker image, and performs dependency and container vulnerability scanning.

Full technical detail, including every schema decision and its reasoning, is in [`docs/architecture.md`](docs/architecture.md).

## Running Locally

```bash
git clone https://github.com/abel05-droid/double-entry-ledger.git
cd double-entry-ledger
docker compose up -d
```

The app will be available at `http://localhost:8080`, with Swagger UI at `/swagger-ui.html`.

Demo credentials, seeded for trying the API and not real accounts:

| Username | Password  | Role   |
| -------- | --------- | ------ |
| admin    | admin123  | ADMIN  |
| viewer   | viewer123 | VIEWER |

Log in via `POST /api/v1/auth/login`, then use the returned token in Swagger UI's Authorize button to try protected endpoints. Full request and response examples are in [`docs/api-examples.md`](docs/api-examples.md).

## Testing

Every layer is tested against a real Postgres and Kafka stack via docker compose. No mocked databases hiding real behavior.

```bash
docker compose up -d
./mvnw test
```

## Design Decisions

A few choices worth knowing the reasoning behind, since interviewers ask why more than what.

* **Derived balances instead of a stored balance column.** Eliminates an entire class of bugs where a stored number quietly drifts out of sync with reality.
* **Database enforced idempotency, not in memory deduplication.** Survives restarts and works correctly across multiple app instances.
* **Kafka events published only after commit**, using Spring's `@TransactionalEventListener(AFTER_COMMIT)`. I intentionally stopped at this pattern because it demonstrates the dual write problem and a common mitigation without adding the complexity of a full Outbox implementation. The tradeoff between the two is discussed in `docs/architecture.md`.
* **JWT plus role based access control.** Posting transactions requires a privileged role. Reading balances doesn't.

## Engineering Challenges

Bugs I actually found and fixed while building this.

**1. Idempotency fingerprint mismatch**

Problem: a test that should have passed kept failing with a false conflicting payload error, even though the request was identical.

Root cause: two implementations of the same hashing logic, one in production code and one hand copied into a test, looked byte for byte identical in every editor but produced different SHA 256 output.

Diagnosis: compared the actual hash lengths and traced both code paths line by line until I found an invisible Unicode character difference between the two.

Fix: deleted the duplicate test logic entirely and had the test call the real production method via reflection, so the two can never silently diverge again.

Lesson: duplicated logic is a liability even when it looks identical. The fix should remove the duplication, not just patch the symptom.

**2. Timestamps coming back null right after creation**

Problem: a POST request would return a journal entry with `createdAt: null`, but a GET on that same entry a moment later showed a real timestamp.

Root cause: Hibernate's `@CreationTimestamp` only gets populated when the SQL insert actually flushes, which happens at transaction commit, after the service method had already built and returned its response object.

Fix: added a deliberate re read after the posting transaction commits, and documented it as a known tradeoff.

**3. A race condition in duplicate request detection**

Problem: my own concurrency test caught a case where two requests sharing the same idempotency key and the same reference ID triggered the wrong database constraint, breaking my duplicate detection logic.

Root cause: I was detecting whether a failure was a legitimate race by checking which specific database constraint fired. Too narrow, since insert ordering meant a different constraint could fire first.

Fix: changed the check to whether a winning row for this exact key exists now, which is correct regardless of which constraint actually tripped.

**4. Spring Security silently not activating in a test**

Problem: adding JWT auth broke an unrelated, previously passing concurrency test from an earlier phase.

Root cause: that test boots a non web Spring context (`webEnvironment = NONE`), and Spring Boot's implicit security auto configuration only activates for real web applications, so a controller needing an `AuthenticationManager` bean failed to wire correctly.

Fix: made the security configuration explicit with `@EnableWebSecurity` instead of relying on Spring's implicit detection.

More detail on each of these, plus a few smaller ones, is in `docs/architecture.md`.

## Development Process

I built this over eight phases using Claude Code as an AI assisted coding tool. I defined the project scope, refined prompts, reviewed generated code, and verified the implementation through testing and debugging. The debugging stories above are all issues I personally diagnosed while building the project. The Git history shows the project being built incrementally across eight phases.

## What's Not Built On Purpose

This is a portfolio project, not a production deployment, and I've tried to be upfront about the line rather than pretend it's more finished than it is. Not implemented: refresh tokens, password reset, secrets management beyond environment variables, database backup and recovery strategy, and the full Outbox Pattern for guaranteed delivery event publishing.

## Further Reading

* [`docs/architecture.md`](docs/architecture.md): full technical reference covering schema, every design decision and its reasoning, concurrency strategy, observability setup, and security model
* [`docs/api-examples.md`](docs/api-examples.md): example requests and responses for every endpoint
* [`docs/load-testing.md`](docs/load-testing.md): load test methodology and measured results
* [`docs/posting-flow.md`](docs/posting-flow.md): the posting request lifecycle, step by step
