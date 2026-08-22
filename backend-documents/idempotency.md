# Idempotency-Key — Concept, Rationale, and Implementation

Cross-cutting concept doc, not tied to one service — wallet-service, fx-rate-service,
conversion-orchestrator, merchant-payment-service, and ledger-service all implement this the
same way (deliberate independent copies of the same `IdempotencyGuard` class, not a shared
library — see each service's implementation-notes doc).

## What is idempotency

An operation is **idempotent** if doing it once has the same effect as doing it N times.
`GET /wallets/{id}/balance` is naturally idempotent — reading a value twice doesn't change it.
`POST /wallets/{id}/debit` is **not** naturally idempotent — calling it twice debits twice.

An **Idempotency-Key** is how a client makes a non-idempotent operation safe to retry: a
client-generated id sent with the request, identifying "this one logical attempt" separately
from "this HTTP call." The server remembers that key's outcome, so a second call with the same
key returns the first call's result instead of repeating the side effect.

## Why this matters here specifically

This is a payments platform. The concrete failure mode this defends against:

1. Client calls `POST /wallets/{id}/debit` for $50.
2. Server debits the wallet, starts writing the response.
3. The network drops before the client receives that response.
4. The client — correctly, per normal HTTP semantics — doesn't know if the debit happened. It
   retries the exact same request.
5. **Without an Idempotency-Key**: the server has no way to tell "new debit" from "retry of one
   that already succeeded." It debits again. The customer is charged twice for one thing.
6. **With an Idempotency-Key**: the retry carries the same key as the original. The server
   recognizes it, skips re-running the debit, and returns the original response. Charged once.

This is not a hypothetical — it's the normal operating condition for a payments system, not an
edge case. Mobile networks drop. Load balancers time out and clients retry. Users double-tap
"Pay" buttons. A distributed SAGA (the Conversion Orchestrator, once built) will retry failed
steps as a matter of course. Every one of those is a retried request with the *same intent*, and
an Idempotency-Key is the mechanism that makes retrying safe rather than dangerous.

Design doc §6.2.3 names this explicitly as one of four concurrency hazards the whole platform is
built to close (alongside lost updates on wallet balance, FX rate races, and out-of-order event
processing).

## Why this is not the same problem the business layer already solves

Both services had *some* protection against duplicates before this was added, and it's worth
being precise about why that wasn't enough on its own:

- **Wallet creation**: the `(userId, currency)` DB unique constraint stops a second wallet from
  being created for the same user+currency. But a retried `createWallet` call for a wallet that
  *already exists because the first call already succeeded* now gets a **409 DUPLICATE_WALLET
  error** — a false failure on a call that should have looked like success from the client's
  point of view.
- **FX rate lock**: the `transaction_id` UNIQUE constraint on `fx_rate_lock` stops a second lock
  row for the same transaction. A retry after success hits **409 RATE_LOCK_CONFLICT** — same
  problem, an error where a replay should be.
- **Reservation capture/consume**: both are one-way state machines (`HELD → CAPTURED`,
  `ACTIVE → CONSUMED`). A retry after success hits the state guard and gets a **409 error**
  (`INVALID_RESERVATION_STATE` / `RATE_LOCK_NOT_ACTIVE`) instead of the original result.

The pattern: business-layer uniqueness/state-machine checks are good at preventing a *second,
distinct* side effect from happening — they are not designed to make a *retry of the same
request* look like success. That second property is specifically what Idempotency-Key adds.
Where the two mechanisms overlap, they're complementary, not redundant: the unique constraint is
what actually stops a duplicate from existing in the database even under a real race; the
Idempotency-Key is what stops the client from seeing an error for what should behave like one
successful call.

The two counter-examples: fx-rate-service's `releaseLock` and (in a different sense) any GET
endpoint don't need a key at all —

- `releaseLock` is idempotent *at the business layer already* (releasing an already-released
  lock is a defined no-op, `200` both times — see `fx-rate-service-api/04-release-lock.md`). A
  key would be pure overhead there.
- GET endpoints (`getBalance`, `getCurrentRate`) are naturally idempotent — reading doesn't
  mutate anything, so there's nothing a retry could duplicate.

Contrast wallet-service's `releaseReservation`, which is **not** idempotent at the business
layer (releasing an already-released reservation throws `INVALID_RESERVATION_STATE`) — that one
genuinely needs the header for the same safety fx-rate's naturally-idempotent release gets for
free.

## How it's implemented

### Mechanics (design doc §6.2.3)

```
Client sends:  Idempotency-Key: <client-generated-id>

Server (IdempotencyGuard.runIdempotent):

  1. SETNX  idem:{key} = "IN_PROGRESS"   (atomic, 24h TTL)
       │
       ├─ won the SETNX (key was free)
       │     → run the real operation
       │     → on success: overwrite idem:{key} with the serialized response
       │     → on failure: DELETE idem:{key}  (release - see "only success is cached" below)
       │
       └─ lost the SETNX (key already exists)
             → GET idem:{key}
             ├─ value is a cached response  → return it directly, operation not re-run
             └─ value is still "IN_PROGRESS" → throw IdempotencyKeyInProgressException (409)
```

The atomicity of `SETNX` is what makes this safe under real concurrency: if two requests with
the same key arrive at the same instant, exactly one of them wins the reservation and runs the
operation; the other sees the key already taken (either `IN_PROGRESS` or, if it lost by enough
of a margin, the finished result) — they can never both run the mutation.

### Where it lives

`IdempotencyGuard` (`com.paymentplatform.wallet.idempotency` / `com.paymentplatform.fxrate.idempotency`),
backed by Spring Data Redis's `StringRedisTemplate`. Exposes the design doc's named primitives
plus a convenience wrapper:

```java
public <T> Optional<T> checkAndReserve(String idempotencyKey, Class<T> responseType);
public void confirm(String idempotencyKey, Object response);
public void release(String idempotencyKey);

// composes the three above - this is what every controller method actually calls
public <T> T runIdempotent(String idempotencyKey, Class<T> responseType, Supplier<T> action);
```

A controller method's whole integration is one call:

```java
@PostMapping("/{walletId}/debit")
public WalletResponse debit(@RequestHeader("Idempotency-Key") String idempotencyKey,
                             @PathVariable String walletId, @Valid @RequestBody DebitRequest request) {
    return idempotencyGuard.runIdempotent(idempotencyKey, WalletResponse.class, () -> {
        Wallet wallet = walletService.debit(walletId, request.amount(), request.transactionId());
        return WalletResponse.from(wallet);
    });
}
```

`@RequestHeader("Idempotency-Key")` with no `required = false` makes the header mandatory —
Spring throws `MissingRequestHeaderException` if it's absent, mapped by `GlobalExceptionHandler`
to `400 VALIDATION_FAILED`.

### Deliberate simplification: only success is cached

If the wrapped action throws, `runIdempotent` **releases** the key (deletes it) rather than
caching the failure. This is a conscious deviation from the design doc's literal wording ("if
the first attempt already finished, the cached result is returned" — doesn't distinguish
success from failure).

Why: caching a failure would mean a transient error (a lost connection mid-request, an
optimistic-lock conflict, a momentarily-busy distributed-lock mutex) permanently poisons that
key — every future retry, even after whatever caused the failure has cleared up, would replay
the old error forever. Releasing on failure means a corrected/retried request gets a genuinely
fresh attempt. This is safe: nothing succeeded on the failed attempt, so there is no side effect
a retry could duplicate.

Verified manually (see each service's implementation-notes doc): a debit for more than the
balance returns 422 and releases its key; retrying that *same* key immediately after with a
valid amount succeeds, rather than replaying the 422 forever.

### Where Redis lives

One shared `redis` container (`backend/docker-compose.yml`), not one per service — matches the
design doc's system block diagram, which groups Redis with shared platform infrastructure
(alongside Oracle/Postgres-per-service being the thing that *is* owned per service). Each
service prefixes its keys (`wallet:idem:` / `fxrate:idem:`) so the same key value used by two
different clients against two different services can never collide.

This is a *different* Redis usage than fx-rate-service's `DistributedLockManager` (an in-memory
placeholder for a future Redisson `RLock`, design doc §6.2.2) — that one is about serializing
concurrent *creation* of a rate lock for the same currency pair, a completely different problem
from replaying a retried request. Don't conflate the two when reading fx-rate-service's code.

## Which endpoints need one, and why

| Endpoint | Needs `Idempotency-Key`? | Reasoning |
|---|---|---|
| wallet `createWallet`, `debit`, `credit`, `reserve`, `captureReservation`, `releaseReservation` | Yes | All mutate state; none idempotent at the business layer for a same-outcome retry (see above). |
| wallet `getBalance` | No | Read-only, naturally idempotent. |
| fx-rate `lockRate`, `consumeLock` | Yes | Mutate state; retry-after-success would otherwise hit a business-layer error, not a replay (see above). |
| fx-rate `getCurrentRate` | No | Read-only, naturally idempotent. |
| fx-rate `releaseLock` | No | Already idempotent at the business layer by explicit design (design doc §6.4) — a key would add nothing. |
| orchestrator `startConversion` (`POST /conversions`) | Yes | Starts and runs the entire saga to a terminal state; a retry must replay the whole saga's already-computed result (design doc §5.3 flow diagram, step 2), not re-run it. |
| orchestrator `getConversion` (`GET /conversions/{id}`) | No | Read-only, naturally idempotent. |
| merchant-payment `pay` (`POST /merchant-payments`) | Yes | Not called out in the design doc's REST contract table for this endpoint (it lists only the `transaction_id` UNIQUE constraint) — added anyway, same reasoning as fx-rate's `lockRate`: the constraint alone turns a legitimate retry into a `409` error, not a replay. See merchant-payment-service-implementation.md for the full note on this deviation. |
| merchant-payment `refund`, `getPayment` | No | `refund` is already idempotent at the business layer (no-op on an already-`REFUNDED` payment, design doc §6.4); `getPayment` is read-only. |
| ledger `postEntries` (`POST /ledger/entries`) | Yes | Same reasoning as merchant-payment's `pay`: the design doc doesn't call out a header for this endpoint, but the append-only `LedgerConflictException` guard alone would turn a legitimate retry into a `409` error rather than a replay. See ledger-service-implementation.md. |
| ledger `getStatement` | No | Read-only, naturally idempotent. |

## Testing without a real Redis

Unit-tested by mocking `StringRedisTemplate` and its nested `ValueOperations` (Mockito) — see
`testing-guide.md`'s "Pattern 4 — testing a Redis-backed component without real Redis" for the
full pattern and code. `IdempotencyGuardTest` exists in all five services (9 tests in
wallet-service, 8 in fx-rate-service, 7 in conversion-orchestrator, 7 in
merchant-payment-service, 7 in ledger-service, identical structure since the classes are
deliberate copies).

This proves `IdempotencyGuard`'s own logic is correct given whatever `StringRedisTemplate`
returns — it does **not** prove the real `SETNX` race resolves correctly against a real Redis
server under genuine concurrent load. That's a Testcontainers integration-test gap, tracked as a
deliberate "what's next" item in each service's implementation notes, not yet closed. The
saga-level replay (a retried `POST /conversions` returning the already-computed result without
re-running the saga) *was* verified manually against a real Redis instance — see
conversion-orchestrator-implementation.md's "Verification performed".

## Related docs

- `wallet-service-implementation.md` / `fx-rate-service-implementation.md` /
  `conversion-orchestrator-implementation.md` / `merchant-payment-service-implementation.md` /
  `ledger-service-implementation.md` — the "Idempotency-Key" section in each, with the manual
  `curl`-against-real-Redis verification performed.
- `wallet-service-api/01-create-wallet.md` (and siblings 03-07) / `fx-rate-service-api/02-lock-rate.md`
  (and 03-consume-lock.md) — per-endpoint request/error-table detail.
- `testing-guide.md` — Pattern 4, the mocking approach used in `IdempotencyGuardTest`.
