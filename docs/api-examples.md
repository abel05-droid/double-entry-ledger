# API Examples

Base URL below assumes the app is running locally on the default port
(`http://localhost:8080`). All examples use these two accounts:

- `11111111-1111-1111-1111-111111111111` — an `ASSET` account ("Cash"), `USD`
- `22222222-2222-2222-2222-222222222222` — a `REVENUE` account ("Sales"), `USD`

Full interactive documentation is at `/swagger-ui.html`; the raw OpenAPI
spec is at `/v3/api-docs`.

## POST /api/v1/journal-entries

Posts a new, balanced journal entry.

**curl**

```bash
curl -i -X POST http://localhost:8080/api/v1/journal-entries \
  -H "Content-Type: application/json" \
  -d '{
    "idempotencyKey": "3f29b6b0-3c22-4e7a-9c1a-6e6f6a2b9b11",
    "referenceId": "INV-100245",
    "description": "Invoice #100245 payment",
    "debitEntries": [
      { "accountId": "11111111-1111-1111-1111-111111111111", "amount": "100.00", "currency": "USD" }
    ],
    "creditEntries": [
      { "accountId": "22222222-2222-2222-2222-222222222222", "amount": "100.00", "currency": "USD" }
    ]
  }'
```

**HTTPie**

```bash
http POST :8080/api/v1/journal-entries \
  idempotencyKey=3f29b6b0-3c22-4e7a-9c1a-6e6f6a2b9b11 \
  referenceId=INV-100245 \
  description="Invoice #100245 payment" \
  debitEntries:='[{"accountId":"11111111-1111-1111-1111-111111111111","amount":"100.00","currency":"USD"}]' \
  creditEntries:='[{"accountId":"22222222-2222-2222-2222-222222222222","amount":"100.00","currency":"USD"}]'
```

**Response — `201 Created`**

```
HTTP/1.1 201 Created
Location: http://localhost:8080/api/v1/journal-entries/9c3e1a3e-5b1e-4b0a-9c2e-4f6a2b9b1122
Content-Type: application/json
```

```json
{
  "id": "9c3e1a3e-5b1e-4b0a-9c2e-4f6a2b9b1122",
  "referenceId": "INV-100245",
  "description": "Invoice #100245 payment",
  "createdAt": "2026-07-08T02:12:44.512Z",
  "entries": [
    {
      "id": "6f6a2b9b-1122-4b0a-9c2e-9c3e1a3e5b1e",
      "accountId": "11111111-1111-1111-1111-111111111111",
      "entryType": "DEBIT",
      "amount": "100.00",
      "currency": "USD",
      "createdAt": "2026-07-08T02:12:44.512Z"
    },
    {
      "id": "1a3e5b1e-4b0a-9c2e-6f6a-2b9b11229c3e",
      "accountId": "22222222-2222-2222-2222-222222222222",
      "entryType": "CREDIT",
      "amount": "100.00",
      "currency": "USD",
      "createdAt": "2026-07-08T02:12:44.512Z"
    }
  ]
}
```

### Reusing an idempotencyKey with the same payload

Replaying the exact request above returns `201 Created` again with the
*same* `id`, `Location`, and body — no duplicate is created.

### Reusing an idempotencyKey with a different payload — `409 Conflict`

```bash
curl -i -X POST http://localhost:8080/api/v1/journal-entries \
  -H "Content-Type: application/json" \
  -d '{
    "idempotencyKey": "3f29b6b0-3c22-4e7a-9c1a-6e6f6a2b9b11",
    "referenceId": "INV-100246",
    "debitEntries": [{ "accountId": "11111111-1111-1111-1111-111111111111", "amount": "250.00", "currency": "USD" }],
    "creditEntries": [{ "accountId": "22222222-2222-2222-2222-222222222222", "amount": "250.00", "currency": "USD" }]
  }'
```

```json
{
  "timestamp": "2026-07-08T02:13:02.019Z",
  "status": 409,
  "error": "Conflict",
  "message": "Idempotency key '3f29b6b0-3c22-4e7a-9c1a-6e6f6a2b9b11' was already used with a different request payload",
  "path": "/api/v1/journal-entries"
}
```

### Unbalanced entries — `422 Unprocessable Entity`

```bash
http POST :8080/api/v1/journal-entries \
  idempotencyKey=$(uuidgen) referenceId=INV-100247 \
  debitEntries:='[{"accountId":"11111111-1111-1111-1111-111111111111","amount":"100.00","currency":"USD"}]' \
  creditEntries:='[{"accountId":"22222222-2222-2222-2222-222222222222","amount":"90.00","currency":"USD"}]'
```

```json
{
  "timestamp": "2026-07-08T02:13:19.884Z",
  "status": 422,
  "error": "Unprocessable Entity",
  "message": "Total debits (100.00) must equal total credits (90.00)",
  "path": "/api/v1/journal-entries"
}
```

### Missing required field — `400 Bad Request`

```json
{
  "timestamp": "2026-07-08T02:13:31.220Z",
  "status": 400,
  "error": "Bad Request",
  "message": "idempotencyKey: idempotencyKey is required",
  "path": "/api/v1/journal-entries"
}
```

## GET /api/v1/journal-entries/{id}

**curl**

```bash
curl -i http://localhost:8080/api/v1/journal-entries/9c3e1a3e-5b1e-4b0a-9c2e-4f6a2b9b1122
```

**HTTPie**

```bash
http :8080/api/v1/journal-entries/9c3e1a3e-5b1e-4b0a-9c2e-4f6a2b9b1122
```

**Response — `200 OK`** — same shape as the `POST` response body above.

**Unknown id — `404 Not Found`**

```bash
curl -i http://localhost:8080/api/v1/journal-entries/00000000-0000-0000-0000-000000000000
```

```json
{
  "timestamp": "2026-07-08T02:14:02.501Z",
  "status": 404,
  "error": "Not Found",
  "message": "No journal entry found with id 00000000-0000-0000-0000-000000000000",
  "path": "/api/v1/journal-entries/00000000-0000-0000-0000-000000000000"
}
```

## GET /api/v1/accounts/{id}/balance

**curl**

```bash
curl -i http://localhost:8080/api/v1/accounts/11111111-1111-1111-1111-111111111111/balance
```

**HTTPie**

```bash
http :8080/api/v1/accounts/11111111-1111-1111-1111-111111111111/balance
```

**Response — `200 OK`**

```json
{
  "accountId": "11111111-1111-1111-1111-111111111111",
  "currency": "USD",
  "balance": "100.00"
}
```

**Unknown account — `404 Not Found`**

```json
{
  "timestamp": "2026-07-08T02:14:31.777Z",
  "status": 404,
  "error": "Not Found",
  "message": "No account found with id 00000000-0000-0000-0000-000000000000",
  "path": "/api/v1/accounts/00000000-0000-0000-0000-000000000000/balance"
}
```

## GET /api/v1/accounts/{id}/ledger

Paginated, newest first by default. `page` (0-based), `size`, and `sort`
(e.g. `sort=amount,asc`) are all optional Spring Data `Pageable` query
parameters.

**curl**

```bash
curl -i "http://localhost:8080/api/v1/accounts/11111111-1111-1111-1111-111111111111/ledger?page=0&size=10"
```

**HTTPie**

```bash
http :8080/api/v1/accounts/11111111-1111-1111-1111-111111111111/ledger page==0 size==10
```

**Response — `200 OK`**

```json
{
  "content": [
    {
      "id": "6f6a2b9b-1122-4b0a-9c2e-9c3e1a3e5b1e",
      "accountId": "11111111-1111-1111-1111-111111111111",
      "entryType": "DEBIT",
      "amount": "100.00",
      "currency": "USD",
      "createdAt": "2026-07-08T02:12:44.512Z"
    }
  ],
  "page": 0,
  "size": 10,
  "totalElements": 1,
  "totalPages": 1,
  "hasNext": false
}
```

Custom sort example (oldest first, by amount ascending):

```bash
curl -i "http://localhost:8080/api/v1/accounts/11111111-1111-1111-1111-111111111111/ledger?sort=amount,asc"
```

**Unknown account — `404 Not Found`**, same shape as the balance endpoint's
404 above, with `path` set to `/api/v1/accounts/{id}/ledger`.
