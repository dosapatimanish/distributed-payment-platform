# End-to-End Flow — From Creating a Wallet to a Recorded Transaction

How the five services work together for one complete money movement: create the wallets, run a
wallet-to-wallet currency conversion (optionally paying a merchant with the result), and record
the whole thing in the double-entry ledger and the saga log.

The "transaction" here is the **conversion SAGA** driven by `conversion-orchestrator` (design
doc §5.3). It calls the other services' REST APIs synchronously, in order, inside one request;
every downstream write carries its own `Idempotency-Key` and publishes a Kafka event. On any
failure it runs compensation to put every balance back.

> **Scope note.** The orchestrator uses **synchronous REST calls**, not async Kafka
> choreography — the services still publish their events (`wallet.*`, `rate.*`, `payment.*`) but
> nothing consumes them yet. See conversion-orchestrator-implementation.md for the deferred
> async design and the three real bugs found building this.

Related: [`README.md`](../README.md) · design doc §5.3 / §6.6 ·
[`idempotency.md`](idempotency.md) · [`kafka-events.md`](kafka-events.md) ·
[`oracle-migration.md`](oracle-migration.md) · each `*-implementation.md`.

---

## Block diagram

```mermaid
flowchart TB
    C(["Client / Postman / curl"])

    subgraph svc["Application services (Spring Boot 4.1.1, Java 25)"]
        ORC["conversion-orchestrator :8083<br/>SAGA engine + state machine"]
        W["wallet-service :8081"]
        FX["fx-rate-service :8082"]
        MP["merchant-payment-service :8084"]
        L["ledger-service :8085"]
    end

    subgraph infra["Shared infrastructure"]
        ORA[("platform-oracle :1521 · paymentdb PDB<br/>schemas: wallet_app · fxrate_app · orchestrator_app · payment_app · ledger_app")]
        RED[("Redis :6379<br/>Idempotency-Key store (24h TTL)")]
        KAF{{"Kafka :9092<br/>domain-event topics"}}
    end

    PROM["Prometheus :9090"] --> GRAF["Grafana :3000"]

    C -->|"① POST /wallets  ×2 (source, destination)"| W
    C -->|"② POST /wallets/{src}/credit — fund the source"| W
    C -->|"③ POST /conversions — run the saga"| ORC
    C -.->|"④ GET /conversions/{id}, GET balances, GET statement"| ORC

    ORC -->|"lock / consume / release rate"| FX
    ORC -->|"debit / credit / spend / reverse"| W
    ORC -->|"charge / refund  (only if merchantId given)"| MP
    ORC -->|"post double-entry / reversal"| L

    W --> ORA
    FX --> ORA
    ORC --> ORA
    MP --> ORA
    L --> ORA

    W -. "wallet.debited / wallet.credited / wallet.debit.failed" .-> KAF
    FX -. "rate.locked / rate.lock.failed" .-> KAF
    MP -. "payment.completed / payment.failed" .-> KAF

    W & FX & ORC & MP & L -. "SETNX / GET idem key" .-> RED
    W & FX & ORC & MP & L -. "/actuator/prometheus" .-> PROM
```

Each service owns one Oracle **schema** in the single shared instance (they connect as their own
user). Redis and Kafka are platform-wide, namespaced/keyed per service.

---

## The complete flow, phase by phase

Worked example: user converts **100.00 USD → INR** at a locked rate of **83.0000**, so
`destAmount = 100.0000 × 83.0000 = 8300.0000 INR`.

### Phase 0 — Provision the wallets  (client → wallet-service)

| # | Call | Notes |
|---|---|---|
| 1a | `POST :8081/api/v1/wallets` `{userId, currency:"USD", highContention:false}` — header `Idempotency-Key: w-src-1` | creates the **source** wallet, `balance = 0`, `status = ACTIVE` |
| 1b | same, `currency:"INR"`, `Idempotency-Key: w-dst-1` | creates the **destination** wallet |
| 2 | `POST :8081/api/v1/wallets/{sourceWalletId}/credit` `{amount:500.00, transactionId:"seed-1"}` — `Idempotency-Key: fund-src-1` | funds the source so the conversion's debit can succeed |

`(userId, currency)` is `UNIQUE`, so a retried create returns a clean replay via the
Idempotency-Key rather than a 409. Balances are `NUMBER(18,4)`.

### Phase 1 — Start the conversion  (client → conversion-orchestrator)

```
POST :8083/api/v1/conversions
Idempotency-Key: conv-1
{ "userId":"user-1",
  "sourceWalletId": "<src>", "destWalletId": "<dst>",
  "sourceCurrency":"USD", "destCurrency":"INR",
  "sourceAmount": 100.00
  /* optional: "merchantId": "merchant-abc"  → also pay a merchant with the result */ }
```

`ConversionController` wraps the **entire** saga in `idempotencyGuard.runIdempotent("conv-1", …)`:

1. `SETNX idem:conv-1 = IN_PROGRESS` (Redis, 24h). If the key already holds a finished
   response, that response is returned and **nothing re-runs**; if it holds `IN_PROGRESS`, the
   caller gets `409`.
2. `INSERT orchestrator_app.conversion_transaction` — new `transactionId` (UUID), `saga_state = STARTED`.

### Phase 2 — The saga (happy path, no merchant)

Each step: one synchronous REST call → persist a `saga_step_log` row → advance `saga_state`
(through `SagaStateMachine`, which rejects any out-of-order move) → save `conversion_transaction`.

| Step | Call (with its own Idempotency-Key) | State after | Kafka |
|---|---|---|---|
| **1. Lock rate** | `POST :8082/api/v1/fx/rate-lock` `{USD, INR, amount:100, transactionId}` · `conv-1-lock` → `{lockId, lockedRate:83.0000}`. Orchestrator computes `destAmount = 8300.0000`. | `RATE_LOCKED` | `rate.locked` (key `transactionId`) |
| **2. Debit source** | `POST :8081/api/v1/wallets/{src}/debit` `{amount:100.00, transactionId}` · `conv-1-debit`. Wallet applies it under optimistic locking (`@Version`) — or `SELECT … FOR UPDATE` if `highContention`. | `SOURCE_DEBITED` | `wallet.debited` (key `walletId`) |
| **3. Credit destination** | `POST :8081/api/v1/wallets/{dst}/credit` `{amount:8300.00, transactionId}` · `conv-1-credit` | `DEST_CREDITED` | `wallet.credited` |
| **4. Record ledger** *(best-effort)* | `POST :8085/api/v1/ledger/entries` · `conv-1-ledger` — the 4-leg posting below. A failure here is logged, `saga_step_log RECORD_LEDGER=FAILED`, and the saga still completes (the money already moved correctly). | unchanged | — |
| **5. Consume lock** *(best-effort, point of no return)* | `POST :8082/api/v1/fx/rate-lock/{lockId}/consume` · `conv-1-consume`. Done **last**, only once no step can still trigger compensation — a `CONSUMED` lock can't be released. | unchanged | — |
| **6. Complete** | — | `COMPLETED` | — |

Then: `SET idem:conv-1 = <serialized 201 body>` (replaces `IN_PROGRESS`), respond `201`.

### Phase 2b — The saga with a merchant charge (`merchantId` present)

Inserted **between step 3 (`DEST_CREDITED`) and step 4**:

| Step | Call | State after |
|---|---|---|
| Charge acquirer | `POST :8084/api/v1/merchant-payments` `{transactionId, merchantId, amount:8300, currency:INR}` · `conv-1-pay`. **Always returns `2xx`** — a decline is `{status:"FAILED"}` in the body, read by the client, not an exception. Approved → `payment.completed`; declined → `payment.failed`. | (see below) |
| Spend the credited funds | on **approved**: `POST :8081/api/v1/wallets/{dst}/debit` `{amount:8300.00, transactionId}` · `conv-1-spend` — the converted money passes through to the merchant, net effect on the destination wallet is zero. | `PAYMENT_COMPLETED` |

On **decline** (or the pay call throwing): `PAYMENT_FAILED` → full compensation (Phase 3b). If
the charge is approved but the spend-debit fails, the orchestrator **refunds the acquirer first**
(`POST /merchant-payments/{paymentId}/refund`), then compensates.

### Phase 3 — What gets recorded (happy path)

**Ledger — one balanced double-entry posting** (`ledger_app.ledger_entry`, append-only). Because
the two wallet legs are in different currencies and can't net against each other, a synthetic
**FX clearing account** (`SYSTEM-FX-CLEARING`) absorbs the conversion so *each currency group
nets to zero on its own*:

| walletId | type | amount | currency | balanceAfter |
|---|---|---|---|---|
| `<src>` | DEBIT | 100.0000 | USD | *source balance after step 2* |
| `SYSTEM-FX-CLEARING` | CREDIT | 100.0000 | USD | 0 |
| `SYSTEM-FX-CLEARING` | DEBIT | 8300.0000 | INR | 0 |
| `<dst>` | CREDIT | 8300.0000 | INR | *dest balance after step 3* |

`transactionId` on all four legs = the saga's `transactionId`. A second `POST` for that
`transactionId` is rejected (`LEDGER_CONFLICT`) — corrections are new offsetting postings, never
mutations. *(The merchant-charge "spend" debit is not ledgered yet — a deliberate follow-up.)*

**Saga log** — `orchestrator_app.saga_step_log`, one row per step attempt: `step_name`,
`status` (`SUCCESS` / `FAILED` / `COMPENSATED`), `payload` (a short summary, or the downstream
`HTTP nnn – {body}` on failure).

**Conversion row** — `orchestrator_app.conversion_transaction` updated in place through the saga:
final `saga_state`, `locked_rate`, `fx_lock_id`, `dest_amount`, timestamps.

**Redis** — `orchestrator:idem:conv-1` now holds the serialized `201` response (24h). Each
downstream service also cached its own step under `wallet:idem:conv-1-debit`,
`fxrate:idem:conv-1-lock`, etc.

**Kafka** — `rate.locked`, `wallet.debited`, `wallet.credited` (and `payment.completed` if a
merchant was paid) were published fire-and-forget. No consumer yet.

### Phase 4 — Observe the result

```
GET  :8083/api/v1/conversions/{transactionId}      → { sagaState:"COMPLETED", lockedRate, destAmount, ... }
GET  :8081/api/v1/wallets/{dst}/balance            → 8300.0000  (or 0 if a merchant was paid)
GET  :8085/api/v1/ledger/wallets/{dst}/statement   → the CREDIT leg above
```

---

## UML sequence diagram — happy path (wallet-to-wallet, no merchant)

```mermaid
sequenceDiagram
    autonumber
    actor Client
    participant ORC as conversion-orchestrator :8083
    participant Redis
    participant FX as fx-rate-service :8082
    participant W as wallet-service :8081
    participant L as ledger-service :8085
    participant Kafka
    participant DB as Oracle · paymentdb

    Note over Client,DB: Pre-req (Phase 0) - source and destination wallets created, source funded

    Client->>ORC: POST /api/v1/conversions  [Idempotency-Key: conv-1]<br/>{sourceWalletId, destWalletId, USD→INR, sourceAmount: 100.00}
    ORC->>Redis: SETNX idem:conv-1 = IN_PROGRESS (24h)
    Redis-->>ORC: acquired (first time)
    ORC->>DB: INSERT conversion_transaction — state = STARTED

    rect rgb(232,244,255)
      Note over ORC,FX: Step 1 — lock the FX rate
      ORC->>FX: POST /api/v1/fx/rate-lock  [conv-1-lock]<br/>{USD, INR, amount: 100, transactionId}
      FX->>DB: INSERT fx_rate_lock (ACTIVE, rate = 83.0000)
      FX--)Kafka: rate.locked  (key = transactionId)
      FX-->>ORC: 201 {lockId, lockedRate: 83.0000}
      ORC->>DB: saga_step_log RATE_LOCK = SUCCESS, state = RATE_LOCKED<br/>destAmount = 100 x 83.0000 = 8300.0000
    end

    rect rgb(232,255,236)
      Note over ORC,W: Step 2 — debit the source wallet
      ORC->>W: POST /api/v1/wallets/{src}/debit  [conv-1-debit]  {amount: 100.00, transactionId}
      W->>DB: UPDATE wallet SET balance = balance - 100 (optimistic @Version)
      W--)Kafka: wallet.debited  (key = sourceWalletId)
      W-->>ORC: 200 {balance}
      ORC->>DB: saga_step_log DEBIT = SUCCESS, state = SOURCE_DEBITED
    end

    rect rgb(232,255,236)
      Note over ORC,W: Step 3 — credit the destination wallet
      ORC->>W: POST /api/v1/wallets/{dst}/credit  [conv-1-credit]  {amount: 8300.00, transactionId}
      W->>DB: UPDATE wallet SET balance = balance + 8300
      W--)Kafka: wallet.credited  (key = destWalletId)
      W-->>ORC: 200 {balance}
      ORC->>DB: saga_step_log CREDIT = SUCCESS, state = DEST_CREDITED
    end

    rect rgb(255,249,230)
      Note over ORC,L: Step 4 — record the double-entry ledger posting (best-effort)
      ORC->>L: POST /api/v1/ledger/entries  [conv-1-ledger]<br/>transactionId, 4 legs: DEBIT src 100 USD / CREDIT clr 100 USD /<br/>DEBIT clr 8300 INR / CREDIT dst 8300 INR
      L->>DB: INSERT 4x ledger_entry  (validator: each currency nets to 0)
      L-->>ORC: 201
      ORC->>DB: saga_step_log RECORD_LEDGER = SUCCESS
    end

    rect rgb(232,244,255)
      Note over ORC,FX: Step 5 — consume the rate lock (last, point of no return)
      ORC->>FX: POST /api/v1/fx/rate-lock/{lockId}/consume  [conv-1-consume]
      FX->>DB: UPDATE fx_rate_lock SET status = CONSUMED
      FX-->>ORC: 200
      ORC->>DB: saga_step_log CONSUME_LOCK = SUCCESS
    end

    ORC->>DB: state = COMPLETED
    ORC->>Redis: SET idem:conv-1 = [serialized 201 body]  (replaces IN_PROGRESS)
    ORC-->>Client: 201 {transactionId, sagaState: COMPLETED, lockedRate: 83.0000, destAmount: 8300.0000}

    Note over Client,DB: Later — poll (naturally idempotent, no key)
    Client->>ORC: GET /api/v1/conversions/{transactionId}
    ORC->>DB: SELECT conversion_transaction
    ORC-->>Client: 200 {sagaState: COMPLETED, ...}
```

### Delta for a merchant charge

Between step 3 and step 4:

```mermaid
sequenceDiagram
    autonumber
    participant ORC as conversion-orchestrator
    participant MP as merchant-payment-service :8084
    participant W as wallet-service :8081
    participant Kafka
    participant DB as Oracle

    Note over ORC,DB: ... state = DEST_CREDITED ...
    Note over MP: mock acquirer approves unless merchantId = acct-decline
    ORC->>MP: POST /api/v1/merchant-payments  [conv-1-pay]<br/>{transactionId, merchantId, amount: 8300, currency: INR}
    MP->>DB: INSERT merchant_payment (COMPLETED)
    MP--)Kafka: payment.completed  (key = transactionId)
    MP-->>ORC: 201 {paymentId, status: "COMPLETED"}  (2xx even on decline, outcome is in the body)
    ORC->>DB: saga_step_log PAYMENT = SUCCESS

    ORC->>W: POST /api/v1/wallets/{dst}/debit  [conv-1-spend]  {amount: 8300.00}
    W--)Kafka: wallet.debited
    W-->>ORC: 200
    ORC->>DB: saga_step_log DEBIT_FOR_PAYMENT = SUCCESS, state = PAYMENT_COMPLETED
    Note over ORC,DB: ... continue to step 4 (record ledger), step 5 (consume lock), COMPLETED ...
```

---

## UML sequence diagram — failure & compensation (declined merchant charge)

Rate is locked, source debited, destination credited — then the acquirer declines. Compensation
unwinds **both** wallet legs, releases the (never-consumed) lock, and records a reversal posting.

```mermaid
sequenceDiagram
    autonumber
    actor Client
    participant ORC as conversion-orchestrator
    participant MP as merchant-payment-service :8084
    participant W as wallet-service :8081
    participant FX as fx-rate-service :8082
    participant L as ledger-service :8085
    participant DB as Oracle

    Note over ORC,DB: rate locked (lk-2), source -100 USD, dest +8300 INR, state = DEST_CREDITED

    ORC->>MP: POST /api/v1/merchant-payments  [conv-2-pay]  {merchantId: "acct-decline", amount: 8300}
    MP-->>ORC: 201 {status: "FAILED"}  (2xx, the decline is in the body)
    ORC->>DB: saga_step_log PAYMENT = FAILED, state = PAYMENT_FAILED
    ORC->>DB: state = COMPENSATING

    rect rgb(255,238,238)
      Note over ORC,W: reverse the destination credit
      ORC->>W: POST /api/v1/wallets/{dst}/debit  [conv-2-compensate-credit]  {amount: 8300.00}
      W-->>ORC: 200
      ORC->>DB: saga_step_log COMPENSATE_CREDIT = COMPENSATED, state = DEST_DEBITED_BACK
    end

    rect rgb(255,238,238)
      Note over ORC,W: reverse the source debit
      ORC->>W: POST /api/v1/wallets/{src}/credit  [conv-2-compensate-debit]  {amount: 100.00}
      W-->>ORC: 200
      ORC->>DB: saga_step_log COMPENSATE_DEBIT = COMPENSATED, state = SOURCE_CREDITED_BACK
    end

    ORC->>FX: DELETE /api/v1/fx/rate-lock/lk-2  (no key, releaseLock is idempotent by design)
    FX->>DB: UPDATE fx_rate_lock SET status = RELEASED
    FX-->>ORC: 200
    ORC->>DB: saga_step_log RELEASE_LOCK = COMPENSATED, state = LOCK_RELEASED

    rect rgb(255,249,230)
      Note over ORC,L: record the reversal (best-effort) — independent posting, id = {transactionId}-reversal
      ORC->>L: POST /api/v1/ledger/entries  [conv-2-ledger-reversal]<br/>4 legs mirroring both reversed sides (each currency nets to 0)
      L-->>ORC: 201
      ORC->>DB: saga_step_log RECORD_LEDGER_REVERSAL = SUCCESS
    end

    ORC->>DB: state = COMPENSATED
    ORC-->>Client: 201 {transactionId, sagaState: COMPENSATED}
```

**Which sides get reversed** depends on where it failed:

| Failure point | State | Reverse credit? | Reverse debit? | Reversal legs posted |
|---|---|---|---|---|
| Rate lock | `FAILED` | — | — | none (nothing moved, saga just ends) |
| Debit source | `DEBIT_FAILED` | no | no | none — only the lock is released |
| Credit dest | `CREDIT_FAILED` | no | yes | 2 (source side) |
| Merchant charge declined / pay call fails | `PAYMENT_FAILED` | yes | yes | 4 (both sides) |

If a compensation step *itself* fails (e.g. `wallet-service` unreachable), the saga stops at
`COMPENSATING` / `DEST_DEBITED_BACK` / `SOURCE_CREDITED_BACK` / `LOCK_RELEASED` — logged at
`ERROR`, **not** auto-retried, and never falsely marked `COMPENSATED`.

---

## Saga state machine

```mermaid
stateDiagram-v2
    [*] --> STARTED
    STARTED --> RATE_LOCKED: lock rate
    STARTED --> FAILED: lock fails (nothing to undo)

    RATE_LOCKED --> SOURCE_DEBITED: debit source
    RATE_LOCKED --> DEBIT_FAILED: debit fails

    SOURCE_DEBITED --> DEST_CREDITED: credit dest
    SOURCE_DEBITED --> CREDIT_FAILED: credit fails

    DEST_CREDITED --> COMPLETED: no merchantId — record ledger, consume lock
    DEST_CREDITED --> PAYMENT_COMPLETED: merchantId — charge approved + wallet spent
    DEST_CREDITED --> PAYMENT_FAILED: charge declined / pay call fails
    PAYMENT_COMPLETED --> COMPLETED: record ledger, consume lock

    DEBIT_FAILED --> COMPENSATING
    CREDIT_FAILED --> COMPENSATING
    PAYMENT_FAILED --> COMPENSATING

    COMPENSATING --> DEST_DEBITED_BACK: reverse credit
    DEST_DEBITED_BACK --> SOURCE_CREDITED_BACK: reverse debit
    COMPENSATING --> SOURCE_CREDITED_BACK: reverse debit (credit never happened)
    COMPENSATING --> LOCK_RELEASED: nothing moved (debit failed)
    SOURCE_CREDITED_BACK --> LOCK_RELEASED: release rate lock
    LOCK_RELEASED --> COMPENSATED: record ledger reversal

    COMPLETED --> [*]
    FAILED --> [*]
    COMPENSATED --> [*]
```

`SagaStateMachine.transition(current, next)` is pure logic over an
`EnumMap<SagaState, Set<SagaState>>`; any move not drawn above is rejected, so a duplicate or
out-of-order call can't corrupt saga state.

---

## Idempotency-Key per step

Client sends one key on `POST /conversions` (e.g. `conv-1`). The orchestrator derives a distinct
key per downstream write — each is a separate HTTP request needing its own safe-retry identity
in the target service's Redis.

| Saga step | Idempotency-Key | Target |
|---|---|---|
| start conversion (wraps the whole saga) | `conv-1` (client-supplied) | orchestrator `POST /api/v1/conversions` |
| lock rate | `conv-1-lock` | fx `POST /api/v1/fx/rate-lock` |
| debit source | `conv-1-debit` | wallet `POST /api/v1/wallets/{src}/debit` |
| credit destination | `conv-1-credit` | wallet `POST /api/v1/wallets/{dst}/credit` |
| charge merchant | `conv-1-pay` | merchant `POST /api/v1/merchant-payments` |
| spend (fund the charge) | `conv-1-spend` | wallet `POST /api/v1/wallets/{dst}/debit` |
| record ledger | `conv-1-ledger` | ledger `POST /api/v1/ledger/entries` |
| consume lock | `conv-1-consume` | fx `POST /api/v1/fx/rate-lock/{id}/consume` |
| compensate credit | `conv-1-compensate-credit` | wallet `POST /api/v1/wallets/{dst}/debit` |
| compensate debit | `conv-1-compensate-debit` | wallet `POST /api/v1/wallets/{src}/credit` |
| record ledger reversal | `conv-1-ledger-reversal` | ledger `POST /api/v1/ledger/entries` (id `{txn}-reversal`) |
| release lock | *(none — idempotent by design)* | fx `DELETE /api/v1/fx/rate-lock/{id}` |
| refund | *(none — idempotent by design)* | merchant `POST /api/v1/merchant-payments/{id}/refund` |

See [`idempotency.md`](idempotency.md) for the `SETNX` mechanics and the "only success is
cached" rule.

---

## What gets recorded, and where

| Store | Location | Written | Contents |
|---|---|---|---|
| Oracle | `orchestrator_app.conversion_transaction` | 1 row/saga, updated in place | userId, wallet ids, currencies, `source_amount`, `dest_amount`, `locked_rate`, `fx_lock_id`, `saga_state`, `idempotency_key` (UNIQUE), timestamps |
| Oracle | `orchestrator_app.saga_step_log` | 1 row per step attempt | `step_name`, `status` (SUCCESS/FAILED/COMPENSATED), `payload` (CLOB — summary or `HTTP nnn – {body}`) |
| Oracle | `wallet_app.wallet` | balance mutated per debit/credit | optimistic (`@Version`) or pessimistic (`SELECT … FOR UPDATE`) locking |
| Oracle | `fxrate_app.fx_rate_lock` | 1 row/lock | `locked_rate`, `status`: `ACTIVE → CONSUMED` (completed) or `RELEASED`/`EXPIRED` |
| Oracle | `payment_app.merchant_payment` | 1 row/charge | `status`: `PENDING → COMPLETED`/`FAILED`/`REFUNDED`, `acquirer_ref` |
| Oracle | `ledger_app.ledger_entry` | 4 rows/completed conversion; 2 or 4 on compensation | append-only double-entry legs incl. the FX clearing-account legs |
| Redis | `<svc>:idem:<key>` | per write | cached response or `IN_PROGRESS`, 24h TTL |
| Kafka | `wallet.*`, `rate.*`, `payment.*` | per write | fire-and-forget events; no consumer yet |

---

## Run it end to end (curl)

```bash
W=http://localhost:8081 ; ORC=http://localhost:8083 ; L=http://localhost:8085

# Phase 0 — two wallets, fund the source
SRC=$(curl -s -XPOST $W/api/v1/wallets -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: w-src-1' \
  -d '{"userId":"user-1","currency":"USD","highContention":false}' | jq -r .walletId)
DST=$(curl -s -XPOST $W/api/v1/wallets -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: w-dst-1' \
  -d '{"userId":"user-1","currency":"INR","highContention":false}' | jq -r .walletId)
curl -s -XPOST $W/api/v1/wallets/$SRC/credit -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: fund-src-1' -d '{"amount":500.00,"transactionId":"seed-1"}' | jq

# Phase 1+2 — run the saga (add "merchantId":"merchant-abc" to also pay a merchant;
#                          use "acct-decline" to force compensation)
TXN=$(curl -s -XPOST $ORC/api/v1/conversions -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: conv-1' \
  -d "{\"userId\":\"user-1\",\"sourceWalletId\":\"$SRC\",\"destWalletId\":\"$DST\",\
\"sourceCurrency\":\"USD\",\"destCurrency\":\"INR\",\"sourceAmount\":100.00}" | tee /dev/stderr | jq -r .transactionId)

# Phase 4 — observe
curl -s $ORC/api/v1/conversions/$TXN | jq          # sagaState: COMPLETED, lockedRate, destAmount
curl -s $W/api/v1/wallets/$DST/balance | jq        # 8300.0000  (0 if a merchant was paid)
curl -s $L/api/v1/ledger/wallets/$DST/statement | jq

# Idempotency — resend Phase 1 with the SAME key: identical body, saga does not re-run
curl -s -XPOST $ORC/api/v1/conversions -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: conv-1' \
  -d "{\"userId\":\"user-1\",\"sourceWalletId\":\"$SRC\",\"destWalletId\":\"$DST\",\
\"sourceCurrency\":\"USD\",\"destCurrency\":\"INR\",\"sourceAmount\":100.00}" | jq
```

The stack must be up first — `cd backend && docker compose up -d` (see
[`README.md`](../README.md) → "How to run it locally"). The combined Postman collection
(`distributed-payment-platform.postman_collection.json`) has all of these requests wired with
variable capture.
