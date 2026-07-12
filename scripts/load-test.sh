#!/usr/bin/env bash
# Load-testing script using `hey` (https://github.com/rakyll/hey).
#
# Exercises, as an authenticated ADMIN:
#   - GET  /api/v1/accounts/{id}/balance   (read-heavy path)
#   - POST /api/v1/journal-entries          (write path)
#
# This measures throughput/latency characteristics, not correctness —
# see PostingServiceConcurrencyTest (docs/architecture.md, "Concurrency")
# for the correctness-under-concurrency work this is distinct from.
#
# KNOWN LIMITATION — read before interpreting POST numbers:
# `hey` sends one static request body to every request in a run; it has
# no per-request templating. This service's idempotency design (see
# docs/architecture.md, "idempotencyKey vs. reference_id") means that
# replaying the *same* idempotencyKey + payload is a defined, successful
# no-op: PostingService returns the original result instead of inserting
# again. So across a `hey` run against POST /api/v1/journal-entries, only
# the very first request performs a real INSERT; every other concurrent
# request in that same run exercises the idempotent-replay fast path
# (unique-constraint-backed lookup, no new row), not a fresh write. That
# fast path is itself a real, meaningful thing to measure — it's what a
# client retry storm against this API looks like, the same scenario
# PostingServiceConcurrencyTest's
# concurrentPostingsWithSameIdempotencyKeySimulateRetryStorm test
# exercises for correctness — but it is NOT sustained distinct-write
# throughput. Measuring genuine concurrent distinct-write throughput
# would require a tool with per-request body templating (e.g. k6, or
# vegeta with a targets file); that's out of scope for this phase, which
# specifically mandates `hey`.
#
# Usage:
#   ./scripts/load-test.sh [base_url]
#
# Environment variables:
#   BASE_URL              Default: http://localhost:8080
#   ADMIN_USERNAME         Default: admin
#   ADMIN_PASSWORD         Default: admin123
#   DEBIT_ACCOUNT_ID       Existing account UUID. If unset, one is created
#                          directly in Postgres via `docker exec` against
#                          the ledger-postgres container (there is no
#                          account-creation REST endpoint to call instead
#                          — see docs/architecture.md's authorization
#                          rules for why one isn't assumed to exist).
#   CREDIT_ACCOUNT_ID      Same as above, for the credit side.
#   REQUESTS               Total requests per hey invocation. Default: 500
#   CONCURRENCY_LEVELS     Space-separated list. Default: "10 50"

set -euo pipefail

BASE_URL="${1:-${BASE_URL:-http://localhost:8080}}"
ADMIN_USERNAME="${ADMIN_USERNAME:-admin}"
ADMIN_PASSWORD="${ADMIN_PASSWORD:-admin123}"
REQUESTS="${REQUESTS:-500}"
CONCURRENCY_LEVELS="${CONCURRENCY_LEVELS:-10 50}"
RESULTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/load-test-results"

command -v hey >/dev/null 2>&1 || { echo "hey is not installed — see https://github.com/rakyll/hey#installation" >&2; exit 1; }
command -v jq >/dev/null 2>&1 || { echo "jq is required" >&2; exit 1; }

mkdir -p "$RESULTS_DIR"

echo "==> Logging in as $ADMIN_USERNAME"
TOKEN=$(curl -sf -X POST "$BASE_URL/api/v1/auth/login" \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"$ADMIN_USERNAME\",\"password\":\"$ADMIN_PASSWORD\"}" | jq -r .token)

if [ -z "$TOKEN" ] || [ "$TOKEN" = "null" ]; then
  echo "Login failed — check credentials and that $BASE_URL is reachable" >&2
  exit 1
fi

if [ -z "${DEBIT_ACCOUNT_ID:-}" ] || [ -z "${CREDIT_ACCOUNT_ID:-}" ]; then
  echo "==> No account ids supplied; seeding two test accounts directly in Postgres"
  SEEDED=$(docker exec ledger-postgres psql -U ledger -d ledger -t -A -F',' -c "
    INSERT INTO accounts (account_number, account_name, account_type, currency)
    VALUES
      ('LOADTEST-DEBIT-$(date +%s)', 'Load Test Debit', 'ASSET', 'USD'),
      ('LOADTEST-CREDIT-$(date +%s)', 'Load Test Credit', 'REVENUE', 'USD')
    RETURNING id;
  ")
  DEBIT_ACCOUNT_ID=$(echo "$SEEDED" | sed -n 1p | tr -d ' ')
  CREDIT_ACCOUNT_ID=$(echo "$SEEDED" | sed -n 2p | tr -d ' ')
fi
echo "==> DEBIT_ACCOUNT_ID=$DEBIT_ACCOUNT_ID  CREDIT_ACCOUNT_ID=$CREDIT_ACCOUNT_ID"

echo
echo "=== GET /api/v1/accounts/{id}/balance ==="
for c in $CONCURRENCY_LEVELS; do
  echo "--- concurrency=$c, n=$REQUESTS ---"
  hey -n "$REQUESTS" -c "$c" \
    -H "Authorization: Bearer $TOKEN" \
    "$BASE_URL/api/v1/accounts/$DEBIT_ACCOUNT_ID/balance" \
    | tee "$RESULTS_DIR/balance_c${c}.txt"
  echo
done

echo
echo "=== POST /api/v1/journal-entries (see limitation note above) ==="
for c in $CONCURRENCY_LEVELS; do
  IDEMPOTENCY_KEY="loadtest-$(date +%s)-$RANDOM"
  REFERENCE_ID="LOADTEST-$(date +%s)-$RANDOM"
  BODY=$(jq -n \
    --arg idempotencyKey "$IDEMPOTENCY_KEY" \
    --arg referenceId "$REFERENCE_ID" \
    --arg debit "$DEBIT_ACCOUNT_ID" \
    --arg credit "$CREDIT_ACCOUNT_ID" \
    '{
      idempotencyKey: $idempotencyKey,
      referenceId: $referenceId,
      description: "load test",
      debitEntries: [{accountId: $debit, amount: "1.00", currency: "USD"}],
      creditEntries: [{accountId: $credit, amount: "1.00", currency: "USD"}]
    }')
  echo "--- concurrency=$c, n=$REQUESTS, idempotencyKey=$IDEMPOTENCY_KEY ---"
  hey -n "$REQUESTS" -c "$c" -m POST \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d "$BODY" \
    "$BASE_URL/api/v1/journal-entries" \
    | tee "$RESULTS_DIR/post_c${c}.txt"
  echo
done

echo "==> Raw results written to $RESULTS_DIR"
