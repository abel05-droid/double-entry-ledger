# double-entry-ledger

A production-grade, immutable double-entry ledger service — the kind of core
bookkeeping infrastructure used by banks and payment processors. Balances are
never stored; they are always derived from an append-only stream of ledger
entries.

> **Status:** Phase 0 — project scaffold. No ledger domain logic yet.

## Tech Stack

- Java 21
- Spring Boot 3
- Maven
- PostgreSQL
- Apache Kafka (KRaft mode)
- Flyway
- Docker Compose
- Lombok
- Spring Data JPA, Spring Web, Spring Validation, Spring Boot Actuator
- JUnit 5 + Testcontainers

## Prerequisites

- JDK 21
- Docker + Docker Compose
- (Optional) Maven 3.9+, or use the bundled `./mvnw` wrapper

## Running locally

### 1. Start infrastructure (Postgres + Kafka)

```bash
docker compose up -d postgres kafka
```

### 2. Run the app

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

The app defaults to `localhost:5432` for Postgres and `localhost:9092` for
Kafka; override with the `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`,
`DB_PASSWORD`, and `KAFKA_BOOTSTRAP_SERVERS` environment variables.

### 3. Verify it's running

```bash
curl http://localhost:8080/
# Ledger Service Running

curl http://localhost:8080/actuator/health
# {"status":"UP"}
```

## Running everything via Docker Compose

```bash
docker compose up --build
```

This builds the app image and starts it alongside Postgres and Kafka.

## Running tests

Tests use Testcontainers and require a running Docker daemon.

```bash
./mvnw test
```

## Database migrations

Schema migrations live in `src/main/resources/db/migration` and run
automatically on startup via Flyway. There is no mutable balance column
anywhere in the schema — account balances will always be computed by
summing ledger entries, never stored directly.

## Project layout

```
src/main/java/com/abel/ledger/
  LedgerApplication.java     application entrypoint
  controller/                REST controllers
  config/                    Spring configuration classes
src/main/resources/
  application.yml            base configuration
  application-dev.yml        local development overrides
  db/migration/               Flyway migrations
src/test/java/com/abel/ledger/
  LedgerApplicationTests.java  context load + smoke tests (Testcontainers)
```

## Roadmap

- **Phase 0** (this phase): runnable scaffold, health endpoint, infra wiring.
- **Phase 1+**: accounts, immutable ledger entries, double-entry posting
  transactions, balance derivation, Kafka event publishing, API surface for
  transfers and account queries.
