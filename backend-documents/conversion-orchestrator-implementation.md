# conversion-orchestrator — Implementation Notes

Third microservice in the platform. Built the same way as the first two: read the relevant
design doc sections (§5.3, §6.1.3, §6.2.3, §6.3.3, §6.6), scaffold, verify manually with `curl`
against real wallet-service + fx-rate-service (+ later merchant-payment-service) instances.

## What this step built

The Conversion Orchestrator, as a standalone Spring Boot 4.1.1 (Java 25) module on port
`:8083`, backed by its own PostgreSQL database (`saga_db`):

- `POST /api/v1/conversions` — start a wallet-to-wallet currency conversion saga, optionally
  followed by a merchant charge.
- `GET /api/v1/conversions/{transactionId}` — poll saga status.
- A real SAGA state machine (`SagaStateMachine`) enforcing the design doc's transition rules.
- Synchronous orchestration: calls wallet-service, fx-rate-service, and (when a `merchantId` is
  given) merchant-payment-service's real REST APIs, in order, within one request — no Kafka
  consumption yet (see Reduced scope below).
- Full compensation logic for every failure point: a failed debit releases the rate lock; a
  failed credit reverses the debit; a declined/failed merchant charge reverses *both* the credit
  and the debit, in that order.
- The same `Idempotency-Key` mechanism as the other services (design doc §6.2.3) on the
  saga-starting endpoint.

**Built in two passes**: first the wallet-to-wallet saga alone (no merchant involved), verified
working end to end; then the optional merchant-payment leg wired in on top, once
merchant-payment-service existed and had been verified standalone. Both passes are covered
below since the second one changed the state machine, the compensation logic, and caught two
more real bugs.

### Reduced scope vs. the design doc — deliberate decisions, made with the user up front

**1. The merchant charge is an additional step after a wallet-to-wallet conversion, not a
replacement for the destination wallet.** The design doc's canonical flow (§5.3) is debit source
→ charge merchant → credit dest → post ledger, with no wallet owned by the customer on the
destination side — "dest" there is really the platform's own settlement bookkeeping. This
implementation instead keeps the two-customer-wallets conversion that was already built and
verified (debit source, credit dest — both the customer's own wallets), and layers an *optional*
merchant charge on top: if the request carries a `merchantId`, the saga spends the just-credited
funds to pay that merchant (crediting then immediately debiting the destination wallet by the
same amount, net zero) instead of leaving them there. If `merchantId` is absent, the saga
behaves exactly as it did before this pass — plain wallet-to-wallet, unaffected.

**2. Synchronous REST calls, not async Kafka choreography.** Unchanged from the first pass -
see below.

**3. No Ledger integration** - still doesn't exist. `DEST_CREDITED`/`PAYMENT_COMPLETED`
transition straight to `COMPLETED`, skipping the design doc's `LEDGER_POSTED` state.

One correction made to the design doc's own state table while adapting it: §6.6 lists
`RATE_LOCKED`'s failure-side next state as `RATE_LOCK_FAILED` — but `RATE_LOCK_FAILED` doesn't
appear as a defined state anywhere else in the table, and the very next row (`SOURCE_DEBITED`)
correctly pairs a debit-attempt with `DEBIT_FAILED`. Read as a copy-paste slip in the source
document; this implementation uses `RATE_LOCKED → DEBIT_FAILED` instead, which is what the
surrounding rows actually imply.

**Synchronous REST calls, not async Kafka choreography** (unchanged rationale from the first
pass): the design doc's `ConversionSagaOrchestrator` reacts to Kafka events
(`handleRateLocked(evt)`, `handleWalletDebited(evt)`, etc.) via `@KafkaListener` — real async
choreography. This service instead calls each downstream service's REST API directly and
inline, in one request handler, using Spring's `RestClient`. All three downstream services still
publish their Kafka events exactly as before (see `kafka-events.md`) - nothing currently
consumes them; wiring this service to actually consume them and drive the state machine that
way is a clean, well-defined follow-up (see What's next) rather than a rewrite.

### Deliberately deferred (and why)

| Deferred | Why |
|---|---|
| Async Kafka-driven orchestration (`@KafkaListener`) | Explicit scope decision for this pass — see above. |
| Ledger integration | Doesn't exist yet. |
| Crash-recovery / saga resume | If this process dies mid-saga, `conversion_transaction`/`saga_step_log` accurately record where it stopped, but nothing automatically resumes it on restart. Needs the async architecture above — a listener re-entering the flow from persisted state — to do properly. |
| Automatic retry of a failed compensation step | If reversing a debit/credit or releasing a lock itself fails during compensation, the saga is left stuck one or more states short of `COMPENSATED` rather than retried automatically. Logged at ERROR level - this is the one failure mode that can actually leave money in the wrong place if nobody follows up. |
| Transactional Outbox | Same category as the other services' deferred Outbox - not built until something needs reliable delivery of *this* service's own events (it doesn't publish any yet). |
| Testcontainers integration tests | Same scope decision as the other services - unit tests only for this pass, see Automated tests below. |
| Flyway/Liquibase | `ddl-auto=update`, same deliberate temporary choice as the other services - and, per one of the bugs below, the thing that made one of them possible in the first place. |

## Package layout

```
com.paymentplatform.orchestrator
├── ping/          toolchain-check endpoint
├── domain/        ConversionTransaction, SagaStepLog, SagaState, StepStatus
├── repository/    ConversionTransactionRepository, SagaStepLogRepository
├── saga/          SagaStateMachine (pure logic), InvalidSagaTransitionException
├── client/        WalletServiceClient, FxRateServiceClient, MerchantPaymentServiceClient
│                  + client/dto (local request/response copies for all three downstream services)
├── service/       ConversionService (the saga engine itself)
├── web/           ConversionController + request/response DTO records
├── exception/     custom exceptions + GlobalExceptionHandler
└── idempotency/   IdempotencyGuard, IdempotencyKeyInProgressException (third independent copy - see idempotency.md)
```

## The saga state machine

`SagaStateMachine.transition(current, next)` is pure logic - no Spring, no I/O, a static method
over an `EnumMap<SagaState, Set<SagaState>>` - so it's fully unit-testable without mocking
anything (39 tests, see Automated tests). It rejects any move not explicitly listed, which is
the actual point per the design doc: a duplicate or out-of-order call can't silently corrupt
saga state, it just gets rejected.

```
STARTED ──lock rate──► RATE_LOCKED ──debit──► SOURCE_DEBITED ──credit──► DEST_CREDITED
   │                        │                        │                        │
   └──► FAILED               └──► DEBIT_FAILED         └──► CREDIT_FAILED        ├── no merchantId ──► COMPLETED
        (nothing to               │                          │                  │
         compensate)              │                          │                  └── merchantId present
                                   │                          │                       │
                                   │                          │                       ├── charge approved ──► PAYMENT_COMPLETED ──► COMPLETED
                                   │                          │                       │
                                   │                          │                       └── charge declined ──► PAYMENT_FAILED
                                   │                          │                                                    │
                                   ▼                          ▼                                                    ▼
                              COMPENSATING ◄──────────────────┴────────────────────────────────────────────────────┘
                                   │                    │                              │
                                   │ (nothing debited)   │ (reverse the debit)          │ (reverse credit, then debit)
                                   ▼                    ▼                              ▼
                             LOCK_RELEASED ◄── SOURCE_CREDITED_BACK ◄── DEST_DEBITED_BACK
                                   │
                                   ▼
                             COMPENSATED
```

`ConversionService.transition(txn, next)` is the single call site that invokes the state
machine, sets the new state, and persists it - so a state can never actually change in the
database without going through the validity check first.

## HTTP clients to the other services

`WalletServiceClient` / `FxRateServiceClient` / `MerchantPaymentServiceClient`, all thin
wrappers around Spring's `RestClient` (configured with a base URL per service, injected as a
shared `RestClient.Builder` bean). Local copies of each downstream service's request/response
DTOs live in `client.dto`, trimmed to only the fields this service actually reads, with
`@JsonIgnoreProperties(ignoreUnknown = true)` so the other services adding fields later doesn't
break deserialization here - same "deliberate independent copy, no shared library" pattern used
for `IdempotencyGuard` across all services now.

Every downstream call that mutates state carries its own distinct `Idempotency-Key`, derived
from the saga's own key plus a step suffix (`{key}-lock`, `{key}-debit`, `{key}-credit`,
`{key}-consume`, `{key}-pay`, `{key}-spend`, `{key}-compensate-debit`, `{key}-compensate-credit`)
- each is a genuinely separate HTTP request that needs its own safe-retry identity in the target
service's Redis, not a share of the orchestrator's own top-level key. `releaseLock` and `refund`
don't get one - both target services' equivalent operations are already idempotent by design
(see their own docs), so there's nothing to protect.

**One asymmetry worth knowing**: unlike wallet-service/fx-rate-service, merchant-payment-
service's `pay` endpoint always returns `2xx` regardless of whether the acquirer approved or
declined the charge (a decline is a business outcome, not a request error - see
merchant-payment-service-api/01-pay.md). `MerchantPaymentServiceClient.pay`'s caller therefore
has to inspect `PaymentResponse.isCompleted()`, not catch an exception, to learn the real
outcome - the one client here that doesn't follow the same try/catch shape as the other two.

Failures from the other two clients are caught as `RestClientException` (Spring's common base
for both non-2xx responses and connection-level failures like timeouts/refused connections) and
turned into a human-readable string via `describe()` for the `saga_step_log` payload -
`RestClientResponseException` subtypes carry the actual HTTP status + response body, more
useful for debugging than a bare exception message.

## Two real bugs this second pass caught

Recorded in detail because both are genuinely instructive, not just "fixed a typo" notes.

### Bug 1: a stale CHECK constraint from `ddl-auto=update`

**Symptom**: the very first attempt at a merchant-payment conversion returned `500
INTERNAL_ERROR`, even though the debit and credit had both already succeeded against real
wallets.

**Root cause**: `saga_state` is mapped `@Enumerated(EnumType.STRING)`. When Hibernate first
created the `conversion_transaction` table (during the *first* pass, before `PAYMENT_COMPLETED`
etc. existed), it generated a Postgres `CHECK` constraint listing only the enum values that
existed at that moment. `ddl-auto=update` adds new columns/tables when the entity changes, but
it does **not** widen an existing `CHECK` constraint when an enum gains new values - so the
first time the saga actually tried to persist `saga_state = 'PAYMENT_COMPLETED'`, Postgres
rejected the row outright with `new row ... violates check constraint
"conversion_transaction_saga_state_check"`.

This is the exact caveat already written into every service's `application.properties` comment
next to `ddl-auto=update` ("it never safely drops or renames columns") - just a specific
instance of it nobody had hit yet, because no enum had grown after its table was first created.

**Fix, for local dev**: drop the stale container and volume and let it recreate from scratch
(`docker compose stop orchestrator-postgres && docker compose rm -f orchestrator-postgres &&
docker volume rm backend_orchestrator_postgres_data`, then bring it back up) - acceptable
because this is disposable local dev data, exactly the tradeoff `ddl-auto=update`'s own doc
comment already names as temporary. A real migration tool (Flyway - see the deferred-items
table) would express "widen this constraint" as an explicit, reviewable migration step instead
of requiring a full data wipe; this is one more concrete reason that deferral has a real cost
once a schema is genuinely evolving, not just growing.

### Bug 2: consuming the rate lock too early

**Symptom**: a *declined* merchant payment correctly reversed both wallet balances (verified by
checking real balances before/after - both exactly restored) - but the saga was left at
`SOURCE_CREDITED_BACK` instead of reaching `COMPENSATED`.

**Root cause**: the original code consumed the fx rate lock (`fxRateClient.consumeLock`)
immediately after `DEST_CREDITED`, before knowing whether a merchant charge afterward would
succeed. When the charge was declined, compensation correctly reversed both wallets, then tried
to release the lock - but fx-rate-service correctly rejected that: a `CONSUMED` lock can never
be released ("can't un-consume", by explicit design - see fx-rate-service-api/04-release-lock.md).
The lock-consumption step had already made the rate lock's outcome irreversible before the saga
itself was actually done deciding whether it would succeed.

**Fix**: move the `consumeLock` call to happen only once *every* step that could still trigger
compensation has already succeeded - i.e. after the optional merchant-charge step, immediately
before the final transition to `COMPLETED`, not right after `DEST_CREDITED`. Consuming the lock
now means what it's supposed to mean: "this conversion is definitively not being undone."

**Why this wasn't caught by the unit tests**: `ConversionServiceTest` mocks
`FxRateServiceClient` directly - a mocked `releaseLock` call has no way to know that a real
fx-rate-service would reject it because a *different* mocked method (`consumeLock`) was called
earlier in the same test's control flow that a real implementation would actually correlate.
This is the same category of gap already named in `testing-guide.md`: unit tests prove the
code's logic given assumed collaborator behavior, not that the real collaborators' *combined*
state machines stay consistent with each other. Both of this pass's bugs were caught by the
same thing - real, manually-run, cross-service verification - not by the automated suite.

## Idempotency-Key

See [idempotency.md](idempotency.md) for the full concept writeup — this is one of four
independent copies of the same `IdempotencyGuard` (design doc §6.2.3). Only `POST /conversions`
needs one; `GET /conversions/{id}` is read-only.

One detail specific to this service: the design doc's SAGA flow diagram (§5.3, step 2) shows
the idempotency check happening *before* any state-changing call - exactly what
`ConversionController` does, wrapping the entire `ConversionService.startConversion(...)` call
(the whole saga, all the way to its terminal state) in `idempotencyGuard.runIdempotent(...)`. A
replayed request returns the saga's already-computed final result (whatever state it ended in -
`COMPLETED`, `FAILED`, or `COMPENSATED`) without re-running any part of it.

## Automated tests

Unit tests only for this pass (same scope decision as the other services): 63 tests total, all
passing (`./mvnw test`).

- **`SagaStateMachineTest`** (39 tests) — pure logic, no mocks, no Spring context. Every valid
  transition in the happy path (with and without a merchant charge) and both/all three
  compensation paths (parameterized), plus explicit rejection tests: skipping a step, a
  re-delivered event after the saga is already terminal, a backwards move, jumping straight to
  a terminal state, and any transition attempted from an already-terminal state.
- **`ConversionServiceTest`** (12 tests) — `WalletServiceClient`/`FxRateServiceClient`/
  `MerchantPaymentServiceClient`/both repositories mocked, no real HTTP, no real DB. One test
  per saga path: full happy path (no merchant), consume-lock-fails-but-still-completes,
  rate-lock-fails, debit-fails, credit-fails, the reversal-itself-fails-stays-stuck case,
  merchant payment approved (asserts the destination wallet is debited back out to pay it),
  merchant payment declined (asserts full reversal in the correct order), the payment-call-itself-
  fails case, and the case where the post-charge wallet debit fails after a real approved charge
  (asserts `refund` gets called before falling back to normal compensation).
- **`ConversionControllerTest`** (5 tests) — `@WebMvcTest(ConversionController.class)`,
  `ConversionService` and `IdempotencyGuard` both mocked with `@MockitoBean`, same passthrough-
  stub pattern as the other services' controller tests.
- **`IdempotencyGuardTest`** (7 tests) — identical structure to the other services' own.

## Schema notes

- `transaction_id` is an app-generated `UUID.randomUUID().toString()`, `VARCHAR(36)` - same
  portability reasoning as every other entity in this platform.
- `idempotency_key` carries a `UNIQUE` constraint (design doc §6.1.3) - belt-and-braces on top
  of the Redis-based check, same spirit as `Wallet`'s `(user_id, currency)` constraint.
- `saga_step_log.payload` is a `CLOB`/`@Lob` - holds either a short success summary or the full
  downstream error description (`HTTP 422 - {...}`), whichever a given step produced.
- No `merchant_id` or `payment_id` column on `conversion_transaction` - not in the design doc's
  schema, and not needed for anything this pass does (no crash-recovery/resume yet to require
  re-deriving them later - see Deliberately deferred). `merchantId` is threaded through as a
  plain method parameter for the duration of one request; `paymentId` shows up in
  `saga_step_log`'s payload for audit, not as a dedicated column.

## How to run it locally

```bash
cd backend
docker compose up -d wallet-postgres fxrate-postgres orchestrator-postgres payment-postgres redis kafka
cd ../wallet-service && ./mvnw spring-boot:run &            # :8081
cd ../fx-rate-service && ./mvnw spring-boot:run &            # :8082
cd ../merchant-payment-service && ./mvnw spring-boot:run &   # :8084
cd ../conversion-orchestrator && ./mvnw spring-boot:run      # :8083
```

orchestrator-postgres publishes on host port **5436** (5432/5433/5434/5435 already taken
locally) - see `backend/docker-compose.yml`. If any port is already in use on startup, see
wallet-service-implementation.md's note on finding and stopping the specific orphaned PID. If
you've grown `SagaState` (or any other `@Enumerated(EnumType.STRING)` field) since this
database was first created, see Bug 1 above before assuming a `500` is something else.

## Verification performed

All done manually via `curl` against real wallet-service, fx-rate-service,
merchant-payment-service, Postgres, and Redis (automated tests are unit-level only, see above):

1. **Happy path, wallet-to-wallet only**: created a USD wallet and an INR wallet for the same
   user, funded the USD wallet, started a conversion for 100 USD, no `merchantId`. Result:
   `sagaState: COMPLETED`, source wallet debited exactly 100.00, destination wallet credited
   exactly `100.00 × lockedRate`, both `createdAt`/`updatedAt` populated correctly on the
   *immediate* response (this is where the `Persistable` bug from the first pass was originally
   caught and fixed).
2. **Debit-fails compensation**: attempted converting an amount far exceeding the source
   wallet's balance. Result: `sagaState: COMPENSATED`, source wallet balance provably unchanged
   before vs. after (rate lock released, nothing to reverse since nothing was ever debited).
3. **Credit-fails compensation**: converted to a destination wallet id that doesn't exist (debit
   succeeds, credit then fails with `WALLET_NOT_FOUND`). Result: `sagaState: COMPENSATED`,
   source wallet balance restored to its exact pre-conversion value.
4. **Idempotency-Key replay**: re-sent the exact same happy-path request with the same key.
   Result: identical `transactionId` and response body back, source wallet balance unchanged -
   the saga did not re-run.
5. **Merchant payment approved**: converted with a normal `merchantId`. Result:
   `sagaState: COMPLETED`, source wallet debited by the source amount, **destination wallet
   balance unchanged** (credited then immediately spent on the charge - verified by comparing
   its exact balance before and after the call).
6. **Merchant payment declined**: converted with the configured decline-merchant-id. Result:
   `sagaState: COMPENSATED`, both source and destination wallet balances provably unchanged
   before vs. after - full reversal, in the correct order (credit undone before debit). This is
   where Bug 2 above was actually caught (first attempt got stuck at
   `SOURCE_CREDITED_BACK`, not `COMPENSATED`) and re-verified clean after the fix.
7. **Idempotency-Key replay, merchant-payment variant**: re-sent an approved-payment request
   with the same key. Result: identical response back, destination wallet balance unchanged
   across the replay - the saga (including the merchant charge) did not re-run.

## What's next

- Async Kafka-driven orchestration - wire `@KafkaListener`s consuming `wallet.debited` /
  `wallet.debit.failed` / `rate.locked` / `rate.lock.failed` / `payment.completed` /
  `payment.failed` (all already published, see kafka-events.md) to drive the same state machine
  reactively instead of synchronously, per the design doc's actual described architecture.
- Crash-recovery / saga resume, once the async architecture above exists to re-enter a
  persisted-but-incomplete saga.
- Automatic retry of failed compensation steps.
- Ledger Service, to build out the design doc's full convert-and-pay flow (`LEDGER_POSTED`) on
  top of what this service already does.
- Testcontainers integration tests against real Postgres/Redis, plus (once async orchestration
  exists) a real Kafka broker. Bug 2 above is exactly the kind of cross-service state
  inconsistency a proper integration test - one that actually exercises fx-rate-service's real
  `CONSUMED`-can't-be-released rule, not a mock of it - would have a chance of catching before
  manual testing did.
