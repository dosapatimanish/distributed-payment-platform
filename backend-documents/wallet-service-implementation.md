# wallet-service — Implementation Notes

Written after the code was built and manually verified, as a reference for future revision
(and for the next service, which will hit some of the same Spring Boot 4.x surprises).

## What this step built

The Wallet Service, as a standalone Spring Boot 4.1.1 (Java 25) module, backed by real
PostgreSQL persistence:

- Wallet CRUD: create, get balance, debit, credit.
- Reservations (holds): reserve → capture (performs the real debit) or release (frees the hold,
  no balance change).
- Both concurrency-control strategies from the design doc: **optimistic locking with retry**
  (default) and **pessimistic locking** (`SELECT ... FOR UPDATE`) for wallets flagged
  `highContention=true`.
- A consistent JSON error shape for every failure path (`GlobalExceptionHandler`).

### Deliberately deferred (and why)

| Deferred | Why |
|---|---|
| ~~Kafka event publishing~~ | **Done** — see [Kafka Events](#kafka-events) below. |
| ~~Redis-backed `Idempotency-Key` handling~~ | **Done** — see [Idempotency-Key](#idempotency-key) below. |
| Transactional Outbox pattern | Only meaningful once there's a Kafka publish step to make reliable. |
| Oracle | Postgres is free, Docker-friendly, and close enough in SQL/locking semantics (including `SELECT ... FOR UPDATE`) to develop against. The schema was kept Oracle-portable on purpose (see below) so the eventual migration is a config/dialect change, not a rewrite. |
| ~~Automated tests~~ | **Done** — see [Automated tests](#automated-tests) below. Was explicit user request to defer for the initial build; verified manually with `curl` instead at the time. |
| Flyway/Liquibase migrations | `spring.jpa.hibernate.ddl-auto=update` is fine while the schema is still moving during learning; must be replaced with real migrations once it stabilizes. |

## Package layout

```
com.paymentplatform.wallet
├── ping/          existing toolchain-check endpoint, untouched
├── domain/        Wallet, WalletReservation, WalletStatus, ReservationStatus
├── repository/    WalletRepository, WalletReservationRepository
├── service/       WalletService  (all business logic + concurrency control)
├── web/           WalletController + request/response DTO records
├── exception/     custom exceptions + GlobalExceptionHandler
├── idempotency/   IdempotencyGuard, IdempotencyKeyInProgressException
└── event/         WalletEventPublisher + event records (WalletDebitedEvent, etc.)
```

One package per layer, matching the design doc's class list. No mapper/facade layer — DTOs
convert themselves via static `from(...)` methods.

## Concurrency control — what was actually built

Both strategies live in `WalletService`, dispatched by a wallet's `highContention` flag:

```java
private Wallet applyMutation(String walletId, UnaryOperator<Wallet> mutation) {
    Wallet probe = walletRepository.findById(walletId).orElseThrow(...);
    return probe.isHighContention()
        ? applyWithPessimisticLock(walletId, mutation)
        : applyWithOptimisticRetry(walletId, mutation);
}
```

**Optimistic retry** (`applyWithOptimisticRetry`): up to 5 attempts, linear backoff
(20ms, 40ms, 60ms, 80ms between attempts). On the 5th failure, throws `WalletConflictException`
→ HTTP 409.

**Pessimistic locking** (`applyWithPessimisticLock`): single attempt, blocks on
`WalletRepository#findByIdForUpdate` (`SELECT ... FOR UPDATE`, 3s lock-wait timeout). A timeout
surfaces as `PessimisticLockingFailureException` → HTTP 409 (fail fast, not queue forever).

### The bug we hit, and the rule it taught

**Both** paths need each attempt to run inside its own real transaction. The natural way to
write that in Spring is `@Transactional` on the method. That does **not** work here, because
`applyMutation` calls `applyWithPessimisticLock`/`applyWithOptimisticRetry` as a plain `this.`
call from inside the same class — Spring's `@Transactional` is implemented as a proxy wrapping
the bean, and a call that never goes back out through the proxy (a "self-invocation") skips the
proxy entirely, so the annotation is silently ignored.

We hit this for real: the pessimistic path was first written as
```java
@Transactional
Wallet applyWithPessimisticLock(...) { ... walletRepository.findByIdForUpdate(...) ... }
```
and every single call to it failed with:
```
jakarta.persistence.TransactionRequiredException: No active transaction
```
because `findByIdForUpdate`'s `SELECT ... FOR UPDATE` ran with no transaction open at all.

**Fix**: build a `TransactionTemplate` from the auto-configured `PlatformTransactionManager` in
the constructor (with `PROPAGATION_REQUIRES_NEW` set explicitly), and call
`transactionTemplate.execute(status -> { ... })` around the actual DB work in both the
optimistic-retry loop and the pessimistic path. `TransactionTemplate.execute()` opens/commits a
transaction programmatically — it doesn't rely on being called through a Spring proxy, so
self-invocation is a non-issue. This also happens to be exactly what the retry loop needs
anyway: each retry attempt gets a genuinely fresh transaction and persistence context, so a
retry always re-reads the row's current `version` instead of reusing stale state from a failed
attempt.

**Takeaway for future services**: any time a `@Transactional` method is invoked directly from
another method in the *same* class (not via an injected reference to the bean), assume the
annotation does nothing, and use `TransactionTemplate` (or restructure into a separate bean)
instead. This is a general Spring pitfall, not specific to this project.

## Idempotency-Key

See [idempotency.md](idempotency.md) for the full concept writeup (what/why/how, shared across
both services) — this section covers only wallet-service-specific detail.

Every write endpoint now requires an `Idempotency-Key` header (design doc §6.2.3) - `POST
/wallets`, `debit`, `credit`, `reserve`, `capture`, `release`. `getBalance` is read-only, no key
needed. Missing header → 400 `VALIDATION_FAILED`.

**Mechanics** (`IdempotencyGuard`, backed by Redis): an atomic `SETNX idem:{key}` reserves the
key as `IN_PROGRESS` (24h TTL). The winner runs the real mutation and, on success, overwrites
the key with the serialized response - any later call with the same key gets that cached
response back (same status/body as the original) instead of re-running the mutation. A call
that arrives while the first is still mid-flight gets `IdempotencyKeyInProgressException` → 409
`IDEMPOTENCY_KEY_IN_PROGRESS` (poll, don't resubmit).

**Deliberate simplification vs. the design doc's literal wording**: only a *successful*
completion is cached. If the mutation throws, `IdempotencyGuard.runIdempotent` releases the key
(deletes it) instead of caching the failure - so a genuinely retried request after a transient
error (a lost connection, an optimistic-lock conflict) gets a fresh attempt instead of being
permanently stuck replaying an old error. Verified manually: a debit for more than the balance
correctly returns 422 and releases its key; retrying the *same* key with a valid amount right
after succeeds, rather than replaying the 422 forever.

**Where it lives**: `IdempotencyGuard` exposes the design doc's named primitives
(`checkAndReserve`, `confirm`) plus a `runIdempotent(key, responseType, action)` convenience
wrapper that composes them - every controller method calls the wrapper, one line each. See
`WalletController`'s class javadoc and `testing-guide.md`'s "Mocking a Redis-backed guard"
pattern for how it's tested without a real Redis.

**Shared Redis instance**: one `redis` container (`backend/docker-compose.yml`) serves both
wallet-service and fx-rate-service - matches the design doc's system diagram, which groups
Redis with shared platform infra rather than owning it per-service like Postgres. Each service
prefixes its keys (`wallet:idem:` / `fxrate:idem:`) so they can't collide.

## Kafka Events

See [kafka-events.md](kafka-events.md) for the full concept writeup (what/why/how, shared
across both services) - this section covers only wallet-service-specific detail.

`WalletService.debit` publishes `wallet.debited` on success or `wallet.debit.failed` on any
failure; `credit` publishes `wallet.credited` on success only (no failure topic exists for
credit per the design doc's topic table). Both keyed by `walletId`. `WalletEventPublisher`
sends plain JSON strings (own `ObjectMapper.writeValueAsString`, not spring-kafka's
`JsonSerializer` - see the Boot 4.1 gotchas section below for why) via a
`KafkaTemplate<String, String>`, async, logging (not throwing) on a failed send.

**`captureReservation` gets its event for free**: it calls `WalletService.debit` internally
(see wallet-service-api's `06-capture-reservation.md`), so capturing a reservation publishes
`wallet.debited`/`wallet.debit.failed` through that same call path - no separate publish logic
needed for it. `createWallet`, `reserveFunds`, and `releaseReservation` publish nothing; they
aren't in the design doc's topic table.

**Deliberate gap, not an oversight**: this is a direct publish after commit, not a
Transactional Outbox - if the process crashes between the DB commit and the Kafka send
completing, the event is lost even though the mutation happened. Still a separate deferred
item (see the table above); closing it is only worth doing once a consumer exists to actually
depend on these events arriving reliably.

## Automated tests

Unit tests only for this pass (no Testcontainers/real-DB/real-Redis/real-Kafka integration
tests yet — that's a separate, larger follow-up): 54 tests total, all passing (`./mvnw test`).

- **`WalletServiceTest`** (25 tests) — Mockito doubles for `WalletRepository`,
  `WalletReservationRepository`, `WalletEventPublisher`, and `PlatformTransactionManager`; no
  Spring context. Covers every business rule (insufficient funds, wallet-not-active vs
  FROZEN-allows-credit, duplicate wallet on both the pre-check and the DB-unique-constraint-race
  paths, the full reservation state machine) plus the two concurrency-control paths themselves:
  asserts `findByIdForUpdate` is used for `highContention=true` wallets and never for ordinary
  ones, and drives the optimistic-retry loop through a losing-then-winning sequence of
  `ObjectOptimisticLockingFailureException`s to prove the retry/backoff/give-up behavior. Also
  asserts `WalletEventPublisher` is called with the right method (published vs. failed) on the
  right outcome for both debit and credit.
- **`WalletControllerTest`** (14 tests) — `@WebMvcTest(WalletController.class)`, `WalletService`
  and `IdempotencyGuard` both mocked with `@MockitoBean`. The guard is stubbed as a passthrough
  by default (runs the action straight through) so most tests can focus on
  `WalletController`/`WalletService` behavior; a few tests override it to prove the
  missing-header (400) and key-in-progress (409) paths specifically. Covers request validation
  and the HTTP status/error-code mapping for every endpoint (`@WebMvcTest` auto-scans
  `@RestControllerAdvice`, so `GlobalExceptionHandler` is exercised for free).
- **`IdempotencyGuardTest`** (9 tests) — `StringRedisTemplate` and its `ValueOperations` mocked
  with Mockito, a real `ObjectMapper`. Covers `checkAndReserve`/`confirm`/`release` individually
  plus `runIdempotent`'s three outcomes (fresh key runs and caches, completed key replays
  without re-running, failed key releases and rethrows).
- **`WalletEventPublisherTest`** (4 tests) — `KafkaTemplate<String, String>` mocked, a real
  `ObjectMapper`. Verifies each publish method sends to the right topic keyed by `walletId` with
  the expected payload fields, plus one test proving a failed/exceptional
  `CompletableFuture` from `kafkaTemplate.send(...)` never propagates out of the publisher (see
  kafka-events.md).

**Mocking `PlatformTransactionManager`**: `WalletService` builds its own `TransactionTemplate`
in the constructor (see the self-invocation section above) rather than using `@Transactional`,
so there's no Spring proxy for a test to intercept. The trick that makes this testable without a
real database: stub `transactionManager.getTransaction(...)` to return a bare mock
`TransactionStatus`. `TransactionTemplate.execute()` only needs that one call to succeed before
it runs the real callback synchronously - everything inside the callback (the repository calls)
is still exercised for real, just without an actual transaction underneath it. The stub is
`lenient()` because several tests (`createWallet`, `getBalance`, most error paths) never reach
`applyMutation` at all, and Mockito's strict-stubs mode would otherwise fail those tests for an
"unnecessary" stub that other tests in the same class do need.

**A mocking mistake worth recording**: the first version of the optimistic-retry test reused one
`Wallet` instance across all retry attempts (`when(walletRepository.findById(...)).thenReturn(...)`
returning the same object every call). Since `debitMutation` mutates the entity in place, the
balance kept compounding across attempts (100 → 90 → 80 → 70) even though two of those three
"transactions" were supposed to have failed and rolled back. Real Postgres wouldn't do this — a
failed commit rolls back, so the next `findById` re-reads the actually-committed balance,
unchanged. Fix: `thenAnswer(...)` returning a **fresh** `Wallet` object on every call, so each
retry attempt starts from the real committed state, the way a fresh `SELECT` would.

## Schema notes

- `wallet_id` / `reservation_id` are app-generated `UUID.randomUUID().toString()` values stored
  as `VARCHAR(36)`, not a Postgres-native `uuid` column — kept portable for the later Oracle
  migration.
- `balance` / `amount` are `BigDecimal` at precision 18, scale 4, matching the design doc's
  `NUMBER(18,4)`. `balance >= 0` is enforced in `WalletService`, not a DB `CHECK` constraint
  (Hibernate's `ddl-auto=update` doesn't manage those reliably across dialects).
- `WalletReservation.walletId` is a plain string FK column, not a JPA `@ManyToOne` — the target
  `Wallet` is always loaded explicitly by `WalletService`, through whichever locking strategy it
  chose, never implicitly through a lazy association.

## Spring Boot 4.1.1 gotchas hit along the way

These cost real debugging time and will very likely resurface in the next service, so they're
recorded here rather than left to be rediscovered:

- **`spring-boot-starter-parent` artifact version has no `.RELEASE` suffix.** Spring
  Initializr's own metadata *labels* the current version `4.1.1.RELEASE`, but the real artifact
  on Maven Central is `4.1.1`. Using the label as the POM version fails dependency resolution.
- **`spring-boot-starter-web` is renamed to `spring-boot-starter-webmvc`** (disambiguating from
  WebFlux).
- **No single `spring-boot-starter-test` any more.** Spring Initializr now generates one test
  starter per feature starter instead (`spring-boot-starter-webmvc-test`,
  `spring-boot-starter-validation-test`, `spring-boot-starter-actuator-test`, ...).
- **Test-slice annotations moved packages.** `@AutoConfigureMockMvc` (and `@WebMvcTest`) now
  live in `org.springframework.boot.webmvc.test.autoconfigure`, not the old
  `org.springframework.boot.test.autoconfigure.web.servlet`. Confirmed by unzipping the actual
  jar (`spring-boot-webmvc-test-4.1.1.jar`) and looking at what classes it contains — the
  reliable way to answer "where did this class go" instead of guessing.
- **`HttpStatus.UNPROCESSABLE_ENTITY` is deprecated** in Spring Framework 7 (which Boot 4.1.1
  pulls in). Used `HttpStatus.valueOf(422)` instead.
- Pattern matching in `switch` over exception types is available on the Java 25 runtime, but
  `GlobalExceptionHandler` retains a plain `if (ex instanceof ...)` chain for clear, stable
  exception mapping behavior.
- **Jackson 3, not Jackson 2 - different base package.** `spring-boot-starter-webmvc` pulls in
  Jackson 3.x, where `ObjectMapper` lives at `tools.jackson.databind.ObjectMapper` (not
  `com.fasterxml.jackson.databind`) and its checked-exception hierarchy is gone -
  `JacksonException` now extends `RuntimeException`, so `readValue`/`writeValueAsString` calls
  in `IdempotencyGuard` need no try/catch at all. Same "unzip the actual jar and grep it"
  approach as the `@WebMvcTest` package move above confirmed this - `jackson-annotations` is
  still `com.fasterxml.jackson.annotation` (that part didn't move), which makes guessing at the
  right import from memory actively misleading here.
- **`spring-kafka`'s `JsonSerializer` is still Jackson 2**, unaffected by any of the above -
  it directly imports `com.fasterxml.jackson.databind.ObjectMapper`, confirmed the same way
  (grepped the class bytecode in `spring-kafka-4.1.1.jar` for package references). Given this
  project has no Jackson 2 anywhere else, pulling it in just for that one serializer felt like
  exactly the kind of mixed-major-version mess worth avoiding - `WalletEventPublisher` uses
  plain `StringSerializer` and does its own JSON encoding with the app's Jackson 3
  `ObjectMapper` instead (see kafka-events.md).

## How to run it locally

```bash
cd backend
docker compose up -d wallet-postgres redis kafka   # this service's Postgres + shared Redis + shared Kafka

cd wallet-service
./mvnw spring-boot:run        # starts the service on :8081
```

If the port is already in use on startup, it's most likely a previous `spring-boot:run` still
running in the background from an earlier session — find and stop that specific PID (`netstat
-ano | grep :8081` on Windows) rather than a blanket "kill all java processes", which can take
down an unrelated JVM (an IDE's language server, another service) along with it.

Sanity checks:
```bash
curl http://localhost:8081/api/v1/ping
curl http://localhost:8081/actuator/health
```

Teardown:
```bash
cd backend
docker compose down           # stop, keep the data volume
docker compose down -v        # stop and wipe the schema/data
```

## Verification performed

All done manually via `curl` (automated tests deferred, see above):

1. Full lifecycle on a normal wallet: create → duplicate-create rejected (409) → credit → get
   balance → debit → insufficient-funds rejected (422) → reserve → release (balance unchanged)
   → reserve → capture (balance decreases by the reserved amount).
2. **Concurrency demo, optimistic wallet**: seeded a normal wallet with exactly $10.00, fired 20
   concurrent $1 debit requests. Result: exactly 10 succeeded (200), 10 correctly rejected as
   insufficient funds (422), final balance exactly `0.0000` — zero lost updates, zero
   over-drafts.
3. **Concurrency demo, high-contention wallet**: same test against a `highContention=true`
   wallet, going through the pessimistic-lock path instead. Same result: exactly 10 succeeded,
   10 rejected, final balance exactly `0.0000`.
4. This is also where the self-invocation `@Transactional` bug above was actually caught — the
   first attempt at step 3 returned HTTP 500 on every request to the high-contention wallet.
5. **Idempotency-Key, against real Redis**: missing header → 400; create-wallet replayed with
   the same key → identical `walletId`/timestamps back, no second wallet created; debit replayed
   with the same key → identical post-debit balance back, no second debit; a debit that fails
   with 422 (insufficient funds) releases its key, so retrying that *same* key with a valid
   amount right after succeeds instead of replaying the old 422 forever.
6. **Kafka events, against a real broker** (`docker compose up -d kafka`, single-node KRaft
   mode): debit success → `wallet.debited`; debit failure → `wallet.debit.failed`, `reason`
   field carries the exception message; credit success → `wallet.credited`. Read back with
   `kafka-console-consumer.sh --from-beginning --property print.key=true` inside the
   `platform-kafka` container - confirmed the message key really is `walletId`, not just a
   payload field of the same name. See kafka-events.md for the fx-rate-service half of this
   verification too.

## What's next

- Testcontainers integration tests against real Postgres, real Redis, and real Kafka, to
  actually exercise `SELECT ... FOR UPDATE`, the DB unique constraints, the idempotency guard's
  `SETNX` race, and partition-key ordering end-to-end (deliberately out of scope for this pass -
  see Automated tests above).
- Transactional Outbox pattern, now that there's a Kafka publish step to make reliable (see
  kafka-events.md's "Deliberate simplification: no outbox, no delivery guarantee").
- Flyway migrations, replacing `ddl-auto=update`, once the schema stabilizes.
- Eventual Oracle migration (schema was kept portable for exactly this).
