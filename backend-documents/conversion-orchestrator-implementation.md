# conversion-orchestrator — Implementation Notes

Third microservice in the platform. Built the same way as the first two: read the relevant
design doc sections (§5.3, §6.1.3, §6.2.3, §6.3.3, §6.6), scaffold, verify manually with `curl`
against real wallet-service + fx-rate-service + Postgres + Redis instances.

## What this step built

The Conversion Orchestrator, as a standalone Spring Boot 4.1.1 (Java 25) module on port
`:8083`, backed by its own PostgreSQL database (`saga_db`):

- `POST /api/v1/conversions` — start a wallet-to-wallet currency conversion saga.
- `GET /api/v1/conversions/{transactionId}` — poll saga status.
- A real SAGA state machine (`SagaStateMachine`) enforcing the design doc's transition rules.
- Synchronous orchestration: calls wallet-service and fx-rate-service's real REST APIs, in
  order, within one request — no Kafka consumption yet (see Reduced scope below).
- Full compensation logic: a failed debit releases the rate lock; a failed credit reverses the
  debit *and* releases the lock.
- The same `Idempotency-Key` mechanism as the other two services (design doc §6.2.3) on the
  saga-starting endpoint.

### Reduced scope vs. the design doc — two deliberate decisions, made with the user up front

**1. Wallet-to-wallet only, no Merchant Payment / Ledger integration.** The design doc's full
saga (§5.3) is convert-**and-pay**: lock rate → debit source → charge merchant → credit dest →
post ledger. Neither Merchant Payment Service nor Ledger Service exist yet. Building the full
flow against services that don't exist would mean stubbing both — instead, this pass builds a
complete, real, working saga for the piece that *can* be real today: converting between two of
the user's own wallets. The state machine reflects this - `DEST_CREDITED` transitions straight
to `COMPLETED`, skipping the design doc's `PAYMENT_*` and `LEDGER_POSTED` states entirely. See
`SagaState`'s javadoc for the exact transition diagram used.

One correction made to the design doc's own state table while adapting it: §6.6 lists
`RATE_LOCKED`'s failure-side next state as `RATE_LOCK_FAILED` — but `RATE_LOCK_FAILED` doesn't
appear as a defined state anywhere else in the table, and the very next row (`SOURCE_DEBITED`)
correctly pairs a debit-attempt with `DEBIT_FAILED`. Read as a copy-paste slip in the source
document; this implementation uses `RATE_LOCKED → DEBIT_FAILED` instead, which is what the
surrounding rows actually imply.

**2. Synchronous REST calls, not async Kafka choreography.** The design doc's
`ConversionSagaOrchestrator` reacts to Kafka events (`handleRateLocked(evt)`,
`handleWalletDebited(evt)`, etc.) via `@KafkaListener` — real async choreography, matching "the
architecture the design doc actually describes." This pass instead calls wallet-service and
fx-rate-service's REST APIs directly and inline, in one request handler, using Spring's
`RestClient`. Chosen because it's simpler to get right and verify correct on the first pass;
wallet-service and fx-rate-service still publish their Kafka events exactly as before (see
`kafka-events.md`) - nothing currently consumes them, but wiring this service to actually
consume them and drive the state machine that way is a clean, well-defined follow-up (see
What's next) rather than a rewrite.

### Deliberately deferred (and why)

| Deferred | Why |
|---|---|
| Async Kafka-driven orchestration (`@KafkaListener`) | Explicit scope decision for this pass — see above. |
| Merchant Payment / Ledger integration | Neither service exists yet. |
| Crash-recovery / saga resume | If this process dies mid-saga, `conversion_transaction`/`saga_step_log` accurately record where it stopped, but nothing automatically resumes it on restart. Needs the async architecture above — a listener re-entering the flow from persisted state — to do properly. |
| Automatic retry of a failed compensation step | If reversing a debit or releasing a lock itself fails during compensation, the saga is left at `COMPENSATING` (or one step short of `COMPENSATED`) rather than retried automatically. Logged at ERROR level - this is the one failure mode that can actually leave money in the wrong place if nobody follows up. |
| Transactional Outbox | Same category as the other two services' deferred Outbox - not built until something needs reliable delivery of *this* service's own events (it doesn't publish any yet). |
| Testcontainers integration tests | Same scope decision as the other two services - unit tests only for this pass, see Automated tests below. |
| Flyway/Liquibase | `ddl-auto=update`, same deliberate temporary choice as the other two services. |

## Package layout

```
com.paymentplatform.orchestrator
├── ping/          toolchain-check endpoint
├── domain/        ConversionTransaction, SagaStepLog, SagaState, StepStatus
├── repository/    ConversionTransactionRepository, SagaStepLogRepository
├── saga/          SagaStateMachine (pure logic), InvalidSagaTransitionException
├── client/        WalletServiceClient, FxRateServiceClient + client/dto (local request/response copies)
├── service/       ConversionService (the saga engine itself)
├── web/           ConversionController + request/response DTO records
├── exception/     custom exceptions + GlobalExceptionHandler
└── idempotency/   IdempotencyGuard, IdempotencyKeyInProgressException (third independent copy - see idempotency.md)
```

## The saga state machine

`SagaStateMachine.transition(current, next)` is pure logic - no Spring, no I/O, a static method
over an `EnumMap<SagaState, Set<SagaState>>` - so it's fully unit-testable without mocking
anything (30 tests, see Automated tests). It rejects any move not explicitly listed, which is
the actual point per the design doc: a duplicate or out-of-order call can't silently corrupt
saga state, it just gets rejected.

```
STARTED ──lock rate──► RATE_LOCKED ──debit──► SOURCE_DEBITED ──credit──► DEST_CREDITED ──► COMPLETED
   │                        │                        │
   └──► FAILED               └──► DEBIT_FAILED         └──► CREDIT_FAILED
        (nothing to               │                          │
         compensate)              ▼                          ▼
                              COMPENSATING ◄───────────────────
                                   │                    │
                                   │ (nothing debited)   │ (reverse the debit)
                                   ▼                    ▼
                             LOCK_RELEASED ◄── SOURCE_CREDITED_BACK
                                   │
                                   ▼
                             COMPENSATED
```

`ConversionService.transition(txn, next)` is the single call site that invokes the state
machine, sets the new state, and persists it - so a state can never actually change in the
database without going through the validity check first.

## HTTP clients to the other two services

`WalletServiceClient` / `FxRateServiceClient`, both thin wrappers around Spring's `RestClient`
(configured with a base URL per service, injected as a shared `RestClient.Builder` bean). Local
copies of each downstream service's request/response DTOs live in `client.dto`, trimmed to only
the fields this service actually reads, with `@JsonIgnoreProperties(ignoreUnknown = true)` so
the other services adding fields later doesn't break deserialization here - same "deliberate
independent copy, no shared library" pattern used for `IdempotencyGuard` across all three
services now.

Every downstream call that mutates state carries its own distinct `Idempotency-Key`, derived
from the saga's own key plus a step suffix (`{key}-lock`, `{key}-debit`, `{key}-credit`,
`{key}-consume`, `{key}-compensate-debit`) - each is a genuinely separate HTTP request that
needs its own safe-retry identity in the target service's Redis, not a share of the orchestrator's
own top-level key. `releaseLock` doesn't get one - fx-rate-service's release is already
idempotent by design (see its own docs), so there's nothing to protect.

Failures are caught as `RestClientException` (Spring's common base for both non-2xx responses
and connection-level failures like timeouts/refused connections) and turned into a human-readable
string via `describe()` for the `saga_step_log` payload - `RestClientResponseException` subtypes
carry the actual HTTP status + response body, which is more useful for debugging than a bare
exception message.

## Idempotency-Key

See [idempotency.md](idempotency.md) for the full concept writeup — this is the third
independent copy of the same `IdempotencyGuard` (design doc §6.2.3), now used by all three
services. Only `POST /conversions` needs one; `GET /conversions/{id}` is read-only.

One detail specific to this service: the design doc's SAGA flow diagram (§5.3, step 2) shows
the idempotency check happening *before* any state-changing call - exactly what
`ConversionController` does, wrapping the entire `ConversionService.startConversion(...)` call
(the whole saga, all the way to its terminal state) in `idempotencyGuard.runIdempotent(...)`. A
replayed request returns the saga's already-computed final result (whatever state it ended in -
`COMPLETED`, `FAILED`, or `COMPENSATED`) without re-running any part of it.

## A real bug this caught: `Persistable` and manually-assigned entity IDs

Worth recording in detail, because it's a genuine Spring Data JPA pitfall that will resurface in
any future entity with an application-assigned (not `@GeneratedValue`) primary key and no
`@Version` field.

**Symptom**: the very first response from `POST /conversions` showed `createdAt` and
`updatedAt` as `null` in the JSON body - but a subsequent `GET` on the same transaction ID
showed both fields correctly populated.

**Root cause**: `ConversionTransaction.transactionId` is an application-generated UUID, set in
the constructor before `save()` is ever called - and unlike `Wallet` (which has a `@Version`
field), `ConversionTransaction` had none. Spring Data JPA's default "is this a new entity?"
check, absent a `@Version` field, falls back to "is the id null?" - which is false the instant
the constructor runs, since the id is already assigned. That makes every `save()` call,
*including the very first one*, look like an update to an existing row, so Spring Data routes it
through `entityManager.merge()` instead of `entityManager.persist()`. `merge()` returns a
*different* managed object with the DB-computed fields (like `createdAt`, set by the entity's
own `@PrePersist` callback) populated on it - the original object instance passed into `save()`
never receives those fields. `ConversionService`'s code, however, kept using that original
instance (`txn`) for the rest of the saga and for the eventual controller response, not
`save()`'s return value - so it looked stamped with real timestamps to the database, but null
in the in-memory object the caller actually got back.

**Fix, two parts**:
1. `ConversionTransaction implements Persistable<String>`, with a transient `isNew` flag
   defaulting `true`, flipped to `false` by the entity's `@PrePersist` callback (first save) and
   by a new `@PostLoad` callback (entities freshly read from the DB are never "new"). This makes
   Spring Data's new-vs-existing decision correct and explicit, independent of the pre-assigned
   id.
2. **Just as important**: `ConversionService` now reassigns `txn` from every `save()` call's
   *return value* (`txn = transactionRepository.save(txn)`, threaded through `transition()`'s
   return value), rather than assuming the object it already held got mutated in place. This is
   the more fundamental fix - `merge()` never reliably mutates the instance you pass to it
   regardless of `isNew()` correctness, so relying on in-place mutation was never actually safe,
   even before the `Persistable` fix. `WalletService`/`FxRateService` never hit this because they
   consistently already used `repository.save(...)`'s return value at every call site - this
   service's code just hadn't, yet.

**Why the unit tests didn't catch this**: `ConversionServiceTest` mocks
`ConversionTransactionRepository`, and the mock's `save()` stub (`thenAnswer(inv ->
inv.getArgument(0))`) faithfully returns exactly what was passed in - which is precisely the
in-place-mutation behavior the real Hibernate `merge()` path does *not* provide. A mock can only
be as realistic as the assumption baked into its stub; this is a concrete instance of the gap
`testing-guide.md`'s "Current gaps" section already names in the abstract (unit tests prove
logic given assumed collaborator behavior, not that the real collaborator behaves that way) -
only the manual, real-Postgres verification below actually caught it.

## Automated tests

Unit tests only for this pass (same scope decision as the other two services): 50 tests total,
all passing (`./mvnw test`).

- **`SagaStateMachineTest`** (30 tests) — pure logic, no mocks, no Spring context. Every valid
  transition in the happy path and both compensation paths (parameterized), plus explicit
  rejection tests: skipping a step, a re-delivered event after the saga is already terminal,
  a backwards move, jumping straight to a terminal state, and any transition attempted from an
  already-terminal state.
- **`ConversionServiceTest`** (8 tests) — `WalletServiceClient`/`FxRateServiceClient`/both
  repositories mocked, no real HTTP, no real DB. One test per saga path: full happy path,
  consume-lock-fails-but-still-completes (best-effort), rate-lock-fails (no compensation),
  debit-fails (release-lock-only compensation), credit-fails (full reversal compensation), and
  the case where the reversal *itself* fails - asserting the saga stays honestly stuck at
  `COMPENSATING` rather than being marked `COMPENSATED` when it isn't.
- **`ConversionControllerTest`** (5 tests) — `@WebMvcTest(ConversionController.class)`,
  `ConversionService` and `IdempotencyGuard` both mocked with `@MockitoBean`, same passthrough-
  stub pattern as the other two services' controller tests.
- **`IdempotencyGuardTest`** (7 tests) — identical structure to the other two services' own
  `IdempotencyGuardTest`.

## Schema notes

- `transaction_id` is an app-generated `UUID.randomUUID().toString()`, `VARCHAR(36)` - same
  portability reasoning as the other two services' entities. See the `Persistable` section above
  for the JPA subtlety this specific choice introduces.
- `idempotency_key` carries a `UNIQUE` constraint (design doc §6.1.3) - belt-and-braces on top
  of the Redis-based check, same spirit as `Wallet`'s `(user_id, currency)` constraint.
- `saga_step_log.payload` is a `CLOB`/`@Lob` - holds either a short success summary or the full
  downstream error description (`HTTP 422 - {...}`), whichever a given step produced.

## How to run it locally

```bash
cd backend
docker compose up -d wallet-postgres fxrate-postgres orchestrator-postgres redis kafka
cd ../wallet-service && ./mvnw spring-boot:run &     # :8081
cd ../fx-rate-service && ./mvnw spring-boot:run &    # :8082
cd ../conversion-orchestrator && ./mvnw spring-boot:run   # :8083
```

orchestrator-postgres publishes on host port **5436** (5432/5433/5434/5435 already taken
locally) - see `backend/docker-compose.yml`. If any port is already in use on startup, see
wallet-service-implementation.md's note on finding and stopping the specific orphaned PID.

## Verification performed

All done manually via `curl` against real wallet-service, fx-rate-service, Postgres, and Redis
(automated tests are unit-level only, see above):

1. **Happy path**: created a USD wallet and an INR wallet for the same user, funded the USD
   wallet, started a conversion for 100 USD. Result: `sagaState: COMPLETED`, source wallet
   debited exactly 100.00, destination wallet credited exactly `100.00 × lockedRate`, both
   `createdAt`/`updatedAt` populated correctly on the *immediate* response (this is where the
   `Persistable` bug above was actually caught and fixed).
2. **Debit-fails compensation**: attempted converting an amount far exceeding the source
   wallet's balance. Result: `sagaState: COMPENSATED`, source wallet balance provably unchanged
   before vs. after (rate lock released, nothing to reverse since nothing was ever debited).
3. **Credit-fails compensation**: converted to a destination wallet id that doesn't exist (debit
   succeeds, credit then fails with `WALLET_NOT_FOUND`). Result: `sagaState: COMPENSATED`,
   source wallet balance restored to its exact pre-conversion value (debit reversed via a
   compensating credit, then the rate lock released).
4. **Idempotency-Key replay**: re-sent the exact same happy-path request with the same key.
   Result: identical `transactionId` and response body back, source wallet balance unchanged -
   the saga did not re-run.

## What's next

- Async Kafka-driven orchestration - wire `@KafkaListener`s consuming `wallet.debited` /
  `wallet.debit.failed` / `rate.locked` / `rate.lock.failed` (all already published, see
  kafka-events.md) to drive the same state machine reactively instead of synchronously, per the
  design doc's actual described architecture.
- Crash-recovery / saga resume, once the async architecture above exists to re-enter a
  persisted-but-incomplete saga.
- Automatic retry of failed compensation steps.
- Merchant Payment Service + Ledger Service, to build out the design doc's full convert-and-pay
  flow (`PAYMENT_*` states, `LEDGER_POSTED`) on top of what this service already does.
- Testcontainers integration tests against real Postgres/Redis, plus (once async orchestration
  exists) a real Kafka broker.
