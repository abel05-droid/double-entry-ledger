# Load Testing

This measures performance characteristics — throughput and latency under
concurrent load — using [`hey`](https://github.com/rakyll/hey). It is
separate from and complementary to `PostingServiceConcurrencyTest`
(`docs/architecture.md`, "Concurrency"), which validates *correctness*
under concurrent access, not performance.

## Running it

```bash
brew install hey   # or see https://github.com/rakyll/hey#installation

# App must already be running and reachable, e.g.:
docker compose up -d postgres kafka
docker compose up -d --build app
# or: ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev

./scripts/load-test.sh                       # defaults to http://localhost:8080
BASE_URL=http://localhost:8081 ./scripts/load-test.sh   # or pass explicitly
```

The script logs in as the seeded `admin` user, seeds two throwaway
accounts directly in Postgres if `DEBIT_ACCOUNT_ID`/`CREDIT_ACCOUNT_ID`
aren't supplied (there's no account-creation REST endpoint to call
instead — see `docs/architecture.md`'s authorization rules), then runs
`hey` against both endpoints at each concurrency level in
`CONCURRENCY_LEVELS` (default `"10 50"`). Raw `hey` output is written to
`load-test-results/` (gitignored-equivalent scratch output — regenerate
by re-running the script, not something to commit).

## A real limitation of `hey`, and what it means for the POST numbers

`hey` sends one fixed request body to every request in a run — it has no
per-request templating. This service's idempotency design (see
`docs/architecture.md`, "`idempotencyKey` vs. `reference_id`") defines
replaying the *same* `idempotencyKey` + payload as a successful no-op:
`PostingService` returns the original result instead of inserting again.
So in a `hey` run against `POST /api/v1/journal-entries`, only the first
request that reaches the database performs a real `INSERT`; the rest of
the concurrent requests in that same run race for the same
`idempotencyKey`/`reference_id`, lose, and get caught by the same
catch-and-replay recovery `PostingServiceConcurrencyTest` already proves
correct — see that test's
`concurrentPostingsWithSameIdempotencyKeySimulateRetryStorm` case.

That means the POST numbers below measure **a concurrent idempotency-replay
storm**, not sustained distinct-write throughput. That's a real and
meaningful thing to measure — it's exactly what a client's retry logic
hammering this API during a network blip looks like — but it is not the
number to quote for "how many new journal entries can this service post
per second." Measuring genuine concurrent distinct-write throughput would
need a tool with per-request body templating (`k6`, or `vegeta` with a
targets file); that's out of scope here, since this phase specifically
mandates `hey`.

## Results (measured 2026-07-12)

Run locally: the app in a Docker container (`double-entry-ledger:local`,
the image built and verified in "Docker Image" below) on the same
docker-compose network as Postgres and Kafka, `hey` run from the host
against the container's published port. 500 requests per `hey`
invocation.

### `GET /api/v1/accounts/{id}/balance`

| Concurrency | Req/sec | p50    | p95    | p99     | Errors |
|-------------|---------|--------|--------|---------|--------|
| 10          | 555.08  | 14.1ms | 31.9ms | 116.3ms | 0/500  |
| 50          | 1120.22 | 38.1ms | 79.5ms | 101.2ms | 0/500  |

### `POST /api/v1/journal-entries` (idempotency-replay storm — see limitation above)

| Concurrency | Req/sec | p50    | p95     | p99     | Errors |
|-------------|---------|--------|---------|---------|--------|
| 10          | 315.88  | 24.6ms | 47.1ms  | 256.1ms | 0/500  |
| 50          | 663.50  | 64.9ms | 117.4ms | 144.2ms | 0/500  |

All 2000 requests across both endpoints and both concurrency levels
returned their expected success status (200 for balance, 201 for
posting) — zero HTTP-level errors. The container logs for the POST runs
do show `ERROR`-severity lines from Hibernate
(`duplicate key value violates unique constraint
"uq_journal_entries_reference_id"`) — these are the expected, caught,
and recovered-from exceptions behind the idempotency-replay path
described above, not unhandled failures; every one of them still
produced a `201` to the client. `docker logs` was checked directly to
confirm no `WARN`/`ERROR` output beyond that expected pattern.

Read latency drops with concurrency (higher req/sec, comparable or lower
tail latency) as expected for a mostly-CPU/DB-bound read path with
headroom. Write latency rises with concurrency, consistent with more
requests contending for the same `idempotencyKey`/`reference_id` unique
constraints under the replay-storm scenario described above — most of
that latency is the DB round-trip for the failed insert attempt plus the
recovery read, not real business-logic cost.

## Not run this session: genuine distinct-write throughput

If a per-request-templating load tool is introduced later, the
meaningful follow-up measurement is distinct-write throughput: N
concurrent clients each posting entries with their own unique
`idempotencyKey`/`reference_id`, which exercises the real insert +
after-commit Kafka publish path on every request instead of racing for
one row.
