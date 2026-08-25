# Unit Testing Guide

How wallet-service, fx-rate-service, conversion-orchestrator, merchant-payment-service, and
ledger-service are unit-tested, written as a reusable reference for whichever service comes next
rather than a report on what already exists. Every pattern below is copy-pasteable — it's what's
actually in `WalletServiceTest`, `WalletControllerTest`, `FxRateServiceTest`,
`FxRateControllerTest`, `ConversionServiceTest`, `MerchantPaymentServiceTest`,
`LedgerServiceTest`, etc.

## Scope decision: unit tests for logic, Testcontainers for the persistence layer

All five services now have **two layers of automated test**, deliberately scoped differently:

| | Unit tests (Mockito) | Testcontainers integration tests (real Postgres) |
|---|---|---|
| Speed | ~2-6s per module | ~7s per module (one real container, reused across that class's tests) |
| Needs Docker at test time | No | Yes |
| Proves | Business logic, validation, error mapping — the code's own decisions given assumed collaborator behavior | The migrated schema (Flyway's `V1__init.sql`) actually matches the entity mappings; unique/check constraints are real, not just declared; `save()` returns an instance with DB-computed fields populated |

The unit tests **simulate** DB behavior (e.g. feeding a mocked repository a scripted sequence of
`ObjectOptimisticLockingFailureException`s) rather than proving Postgres itself does what the
code assumes. Pattern 6 below closes that gap for the persistence layer specifically — one
`@DataJpaTest` + real `PostgreSQLContainer` class per service, exercising each entity's own
repository against a real, Flyway-migrated database.

**Still deferred**: Redis's `SETNX` race and Kafka's actual publish/ordering behavior aren't
covered by Testcontainers yet — `IdempotencyGuardTest` and the event-publisher tests still mock
`StringRedisTemplate`/`KafkaTemplate` (Patterns 4 and 5 below), and the Kafka half of that gap
was only closed *manually* (not by the automated suite) — see kafka-events.md's "Manually
verified" section. A full Redis/Kafka Testcontainers pass is a clean, separate follow-up from
this one.

**A concrete case where this gap actually bit** (now closed): conversion-orchestrator's
`ConversionServiceTest` mocks its repository's `save()` to return exactly what was passed in -
faithful to what a mock *should* do, but not to what Hibernate's real `merge()` path actually
does for an entity with a manually-assigned id and no `@Version` field (it returns a *different*
object with DB-computed fields populated, not the same instance mutated in place). The mocked
test suite passed the whole time; only a real-Postgres manual `curl` test caught that
`createdAt`/`updatedAt` came back `null` on the live response. See
conversion-orchestrator-implementation.md's "A real bug this caught: `Persistable` and
manually-assigned entity IDs" for the full story and fix - worth reading before adding another
entity with an application-assigned id anywhere in this codebase.
`ConversionTransactionRepositoryIntegrationTest` (Pattern 6 below) now asserts exactly this
against a real container, permanently - the exact test that would have caught the bug on day one
had it existed then.

**A second concrete case, caught *while writing* the Testcontainers pass itself** (not before
it): ledger-service's `transaction_id` column being `VARCHAR(36)` originally caused a live
`value too long` error the first time conversion-orchestrator tried to post a `-reversal`-suffixed
id (45 chars) - see conversion-orchestrator-implementation.md's "Bug 3". Widened to `VARCHAR(64)`
at the time, but there was no automated regression guard against it ever shrinking back until
`LedgerEntryRepositoryIntegrationTest`'s `save_45CharReversalStyleTransactionId_fitsInTheColumn`
test (Pattern 6 below) was added - a direct, permanent regression test for that exact bug.

**A second, related case, from wiring merchant-payment-service into the same saga**: a test that
mocks two different downstream clients (`FxRateServiceClient` and `WalletServiceClient`, say)
has no way to know that a *real* fx-rate-service would reject releasing a rate lock that a
*real* call sequence had already marked `CONSUMED` earlier in the same request - each mock only
knows what it was individually stubbed to do, not how the real services' own state machines
would actually interact with each other across calls. This one wasn't a mocking mistake exactly
(the mocks did what they were told) - it's a category of bug that mocked unit tests structurally
cannot catch regardless of how carefully they're written, only real cross-service calls can. See
conversion-orchestrator-implementation.md's "Bug 2: consuming the rate lock too early" for the
full story.

## What's already on the classpath — no pom.xml changes needed

Neither service depends on the classic `spring-boot-starter-test`. Spring Boot 4's modular test
starters split it up, but the granular test starter already in both `pom.xml`s
(`spring-boot-starter-actuator-test`) transitively pulls in `spring-boot-starter-test`, which
brings everything used below: JUnit Jupiter 6, Mockito 5 (+ `mockito-junit-jupiter`), AssertJ,
`spring-test` (MockMvc). Confirmed by running `./mvnw dependency:tree` before writing any test —
worth doing that first in a new service rather than guessing at dependencies to add.

`spring-boot-starter-webmvc-test` (also already present) is what actually supplies `@WebMvcTest`
itself.

## Pattern 1 — service-layer unit test (Mockito, no Spring context)

For a `@Service` class whose collaborators are repositories/other beans: mock every
collaborator, no `ApplicationContext` involved, fast (`WalletServiceTest` runs 22 tests in well
under a second).

```java
@ExtendWith(MockitoExtension.class)
class WalletServiceTest {

    @Mock
    private WalletRepository walletRepository;
    // ... one @Mock per constructor-injected collaborator

    private WalletService walletService;

    @BeforeEach
    void setUp() {
        walletService = new WalletService(walletRepository, /* ... */);
    }

    @Test
    void debit_sufficientFunds_reducesBalance() {
        Wallet wallet = activeWallet("w-1", new BigDecimal("100.0000"), false);
        when(walletRepository.findById("w-1")).thenReturn(Optional.of(wallet));
        when(walletRepository.save(any(Wallet.class))).thenAnswer(inv -> inv.getArgument(0));

        Wallet result = walletService.debit("w-1", new BigDecimal("40.00"), "txn-1");

        assertThat(result.getBalance()).isEqualByComparingTo("60.0000");
    }
}
```

Naming convention used throughout: `methodUnderTest_condition_expectedOutcome`. Read the test
name and you know what it proves without opening the body.

`when(repo.save(any(X.class))).thenAnswer(inv -> inv.getArgument(0))` is the standard
"pretend-persist" stub — returns whatever was passed in, so the mutation the service made is
still visible on the returned object, without a real DB round-trip.

## Pattern 2 — controller-layer slice test (`@WebMvcTest`)

Loads only the one controller plus any `@RestControllerAdvice` in the same package scan (so
`GlobalExceptionHandler` comes along automatically) — not the full application context. The
service layer is a Mockito double.

```java
@WebMvcTest(WalletController.class)
class WalletControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WalletService walletService;

    @Test
    void getBalance_notFound_returns404WithErrorCode() throws Exception {
        when(walletService.getBalance("missing")).thenThrow(new WalletNotFoundException("missing"));

        mockMvc.perform(get("/api/v1/wallets/missing/balance"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WALLET_NOT_FOUND"));
    }
}
```

Use this layer to test **request binding, `@Valid` validation, and the HTTP status/error-code
mapping** for every endpoint — not business logic (that's Pattern 1's job). A controller test
suite that mirrors every row in the service's own error table (see each service's
`backend-documents/*-api/` docs) is the right level of coverage.

### Two Spring Boot 4 import gotchas that cost real time here

- **`@MockitoBean`, not `@MockBean`.** `@MockBean` doesn't exist any more in this Spring
  Framework 7 / Boot 4.1 combo — the class simply isn't on the classpath. Use
  `org.springframework.test.context.bean.override.mockito.MockitoBean`.
- **`@WebMvcTest` moved packages.** It now lives in
  `org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest`, not the old
  `org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest`. When an import doesn't
  resolve and you're not sure where a Boot 4 class moved to, don't guess — unzip the actual jar
  and grep it: `unzip -l spring-boot-webmvc-test-4.1.1.jar | grep WebMvcTest`. That's how this
  was actually found (see wallet-service-implementation.md's "Spring Boot 4.1.1 gotchas"
  section).
- **`status().isUnprocessableEntity()` is deprecated** (Spring Framework 7). Use
  `status().is(422)` instead — same assertion, no deprecation warning.

## Pattern 3 — testing a class with no Spring/mocking need at all

If a class's dependencies are simple, in-memory, and cheap to construct for real (no I/O, no
Spring wiring), just `new` it — no `@Mock`, no Spring context. `FxRateCacheTest` and
`DistributedLockManagerTest` both do this: they're plain `ConcurrentHashMap`-backed classes, so
the "real" object under test in a service-layer test (Pattern 1) can also just be a real
instance instead of a mock, e.g.:

```java
@ExtendWith(MockitoExtension.class)
class FxRateServiceTest {

    @Mock
    private FxRateLockRepository lockRepository; // has a real DB behind it - mock it

    private FxRateCache cache; // simple in-memory class - use the real thing
    private FxRateService fxRateService;

    @BeforeEach
    void setUp() {
        cache = new FxRateCache();
        fxRateService = new FxRateService(cache, new DistributedLockManager(), lockRepository, 10L);
    }
}
```

Mocking something that has no meaningful behavior to fake just adds noise — prefer a real
instance whenever the "real" cost is a few lines of pure Java.

## Pattern 4 — testing a Redis-backed component without real Redis

See `idempotency.md` for what `IdempotencyGuard` is and why it exists - this section is just the
testing technique.

`IdempotencyGuard` (wallet-service and fx-rate-service both have one - deliberate copies of each
other, see each service's implementation-notes doc) depends on Spring Data Redis's
`StringRedisTemplate`. Mock it the same way as any other collaborator - the one wrinkle is that
`RedisTemplate`/`StringRedisTemplate` doesn't expose `get`/`set`/`setIfAbsent` directly; those
live on a nested `ValueOperations` object returned by `opsForValue()`, so that has to be mocked
and wired in too:

```java
@ExtendWith(MockitoExtension.class)
class IdempotencyGuardTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private IdempotencyGuard guard;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        guard = new IdempotencyGuard(redisTemplate, new ObjectMapper(), 24L, "wallet:idem:");
    }

    @Test
    void checkAndReserve_freshKey_reservesAndReturnsEmpty() {
        when(valueOperations.setIfAbsent(eq("wallet:idem:key-1"), eq("IN_PROGRESS"), any(Duration.class)))
                .thenReturn(true);

        assertThat(guard.checkAndReserve("key-1", SampleResponse.class)).isEmpty();
    }
}
```

The `opsForValue()` stub goes in `@BeforeEach` as `lenient()` (Trap 1 above) because not every
test calls it - `release()`, for instance, only calls `redisTemplate.delete(...)` directly.

A real `ObjectMapper` (Jackson 3's `tools.jackson.databind.ObjectMapper` - see the Boot 4.1
gotchas in each service's implementation-notes doc) is used for the (de)serialization the guard
does internally; mocking JSON serialization would just mean re-implementing a fake JSON
formatter by hand for no benefit.

This is real Redis behavior faked at the `StringRedisTemplate` boundary - it does not prove the
actual `SETNX` race resolves correctly under real concurrent load against a real Redis server.
That's exactly the kind of gap Testcontainers integration tests would close (see "Current gaps"
below) - this unit test proves `IdempotencyGuard`'s own logic is correct *given* whatever
`StringRedisTemplate` returns, not that Redis will return what we assume.

## Pattern 5 — testing a Kafka-publishing component without a real broker

See `kafka-events.md` for what `WalletEventPublisher`/`FxRateEventPublisher` are and why they
exist - this section is just the testing technique. Simpler than Pattern 4's Redis case: mock
`KafkaTemplate<String, String>` directly, no nested-operations-object wrinkle. The one thing to
get right is `KafkaTemplate.send(...)`'s return type - a `CompletableFuture`, which the
publisher chains `.whenComplete(...)` onto, so the mock has to return an actual (completed, or
failed) future, not `null`:

```java
@ExtendWith(MockitoExtension.class)
class WalletEventPublisherTest {

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private WalletEventPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new WalletEventPublisher(kafkaTemplate, new ObjectMapper());
    }

    @Test
    void publishDebited_sendsSerializedEventKeyedByWalletId() {
        when(kafkaTemplate.send(eq("wallet.debited"), eq("w-1"), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        publisher.publishDebited(new WalletDebitedEvent("w-1", "txn-1", TEN, TEN, Instant.now()));

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(eq("wallet.debited"), eq("w-1"), payload.capture());
        assertThat(payload.getValue()).contains("\"walletId\":\"w-1\"");
    }

    @Test
    void publish_kafkaSendFailsAsynchronously_doesNotPropagateToCaller() {
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("kafka unreachable")));

        assertThatCode(() -> publisher.publishDebited(/* ... */)).doesNotThrowAnyException();
    }
}
```

That second test matters more than it looks: it's the one thing proving a Kafka outage can
never turn into an unexpected exception bubbling up through a controller that already committed
its DB work. Skipping it would leave that guarantee undocumented and unverified.

Same caveat as Pattern 4: this proves the publisher's own logic, not that a real broker
actually receives, persists, or orders these messages correctly. That was verified once
manually against a real broker instead (see kafka-events.md) - still a Testcontainers-shaped
gap in the automated suite, same category as the Postgres/Redis ones.

## Pattern 6 — repository integration test against a real Postgres container

```java
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class WalletRepositoryIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private WalletRepository walletRepository;

    @Test
    void save_populatesCreatedAtAndUpdatedAt_onTheReturnedInstance() {
        Wallet saved = walletRepository.save(new Wallet(...));

        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
    }

    @Test
    void save_duplicateUserCurrency_violatesRealUniqueConstraint() {
        walletRepository.saveAndFlush(new Wallet(..., "user-1", "EUR", ...));

        assertThatThrownBy(() -> walletRepository.saveAndFlush(new Wallet(..., "user-1", "EUR", ...)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
```

`@DataJpaTest` + `@AutoConfigureTestDatabase(replace = NONE)` + `@Testcontainers` +
`@ServiceConnection` on a `@Container static PostgreSQLContainer` is the whole setup - Spring
Boot wires the container's real JDBC URL into the test context automatically (no manual
`@DynamicPropertySource` needed), Flyway's `V1__init.sql` runs against it at context startup
exactly like production does, and `@AutoConfigureTestDatabase(replace = NONE)` stops
`@DataJpaTest`'s default behavior of trying to swap in an embedded database this project doesn't
have (no H2 anywhere on the classpath).

**Every service gets exactly one of these**, one per its main entity's repository: `wallet-service`
→ `WalletRepositoryIntegrationTest`, `fx-rate-service` → `FxRateLockRepositoryIntegrationTest`,
`conversion-orchestrator` → `ConversionTransactionRepositoryIntegrationTest`,
`merchant-payment-service` → `MerchantPaymentRepositoryIntegrationTest`, `ledger-service` →
`LedgerEntryRepositoryIntegrationTest`. Each asserts the same three things, adapted to that
entity: (1) `save()`'s returned instance has its `@PrePersist`-set timestamp field(s) populated -
the direct regression test for the `Persistable`/merge-vs-persist bug class, worth having even on
entities (like `Wallet`, which has `@Version`) that were never actually susceptible to it; (2) the
entity's real unique/check constraint actually rejects a violation, not just declares one; (3) a
plain `findById`/finder round-trip preserves precision/enum values correctly. `WalletServiceTest`
never talks to a container at all - unit tests keep mocking the repository, this is a separate,
additional test class, not a replacement.

**Boot 4.1.1 / Testcontainers 2.x gotchas hit writing this pattern** (see wallet-service-
implementation.md's own gotchas section for the fuller writeup of the first one):

- `@DataJpaTest` lives at `org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest`,
  `@AutoConfigureTestDatabase` at `org.springframework.boot.jdbc.test.autoconfigure.*` - two
  *different* granular test-starter artifacts (`spring-boot-starter-data-jpa-test` pulls both
  in), not the pre-Boot-4 unified package.
- Testcontainers 2.x renamed its Maven artifacts with a `testcontainers-` prefix
  (`org.testcontainers:testcontainers-junit-jupiter`, `org.testcontainers:testcontainers-postgresql`
  - not the classic bare `junit-jupiter`/`postgresql` artifact ids from 1.x) and moved
  `PostgreSQLContainer` to `org.testcontainers.postgresql.PostgreSQLContainer`, which is no
  longer generic (no more `PostgreSQLContainer<SELF>` self-typed builder pattern) - a plain
  `PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine")`, no `<>`.

## Two mocking traps worth knowing before you hit them

### Trap 1 — `UnnecessaryStubbingException` from a shared `@BeforeEach` stub

Mockito's strict-stubs mode (the JUnit 5 default via `MockitoExtension`) fails a test if a stub
set up in `@BeforeEach` is never actually used by that particular test method. This bites any
time some tests in a class need a shared stub and others don't — e.g. `WalletServiceTest`'s
`transactionManager.getTransaction(...)` stub is only exercised by tests that reach
`applyMutation` (debit/credit/pessimistic-path reserves); `createWallet`, `getBalance`, and most
error-path tests never touch it.

Fix: mark that one stub `lenient()`, not the whole test class:

```java
@BeforeEach
void setUp() {
    lenient().when(transactionManager.getTransaction(any(TransactionDefinition.class)))
            .thenReturn(mock(TransactionStatus.class));
    walletService = new WalletService(walletRepository, reservationRepository, transactionManager, 15L);
}
```

Don't reach for `@MockitoSettings(strictness = Strictness.LENIENT)` on the whole class just to
silence this — it also hides genuinely unused/wrong stubs elsewhere in the same class.
`lenient()` on the one stub that's legitimately conditional is more precise.

### Trap 2 — a mocked repository returning the *same* mutable object across calls

If service code mutates an entity in place (`wallet.setBalance(...)`) and a test stubs
`repository.findById(...)` to return one shared object every time it's called, repeated
mutation-then-"failed"-save cycles will keep compounding on that same instance — even though a
real database would have rolled back and left the row untouched.

This surfaced writing the optimistic-retry test for `WalletService.debit`: the first version
returned one `Wallet` from every `findById` stub call. The retry loop calls `debitMutation`
before `save()` can throw, so balance went 100 → 90 (attempt 1, save throws) → 80 (attempt 2,
save throws) → 70 (attempt 3, save succeeds) — the test then wrongly asserted 90, when the code
was actually correct and the mock was lying about what a rolled-back transaction leaves behind.

Fix: `thenAnswer(...)` returning a **fresh** entity on every call, so each retry attempt starts
from the real, unmutated, "committed" state:

```java
when(walletRepository.findById("w-1"))
        .thenAnswer(inv -> Optional.of(activeWallet("w-1", new BigDecimal("100.0000"), false)));
```

General rule: if the code under test mutates an entity in place and your test has more than one
logical "transaction attempt" touching it, prefer `thenAnswer` returning a new instance over
`thenReturn` handing back one shared instance — unless you specifically want to prove mutation
*is* visible across calls (e.g. `releaseReservation`'s "no wallet mutation happens" test
legitimately reuses one wallet instance to prove nothing changed on it).

## Mocking a `TransactionTemplate`-based method (no `@Transactional`, no proxy to intercept)

Both `WalletService` and (less directly) `FxRateService` avoid `@Transactional` on
self-invoked methods, for the reason recorded in wallet-service-implementation.md (a
self-invocation call never goes through the Spring proxy, so `@Transactional` on it silently
does nothing). They build a `TransactionTemplate` from an injected `PlatformTransactionManager`
instead and call `transactionTemplate.execute(status -> { ... })` directly.

That's easy to make testable without a real transaction manager or database: `execute()` only
needs `transactionManager.getTransaction(...)` to return something before it will run the real
callback synchronously.

```java
@Mock
private PlatformTransactionManager transactionManager;

@BeforeEach
void setUp() {
    lenient().when(transactionManager.getTransaction(any(TransactionDefinition.class)))
            .thenReturn(mock(TransactionStatus.class));
    walletService = new WalletService(walletRepository, reservationRepository, transactionManager, 15L);
}
```

Everything inside the callback — the actual repository calls — is still exercised for real by
the test; only the transaction machinery around it is faked. If you write a new service that
uses this same `TransactionTemplate`-instead-of-`@Transactional` pattern (worth doing any time a
method calls another `@Transactional`-flavored method on `this`), this is the stub to reach for.

## Current test inventory

| Service | Test class | Count | What it covers |
|---|---|---|---|
| wallet-service | `WalletServiceTest` | 25 | Business rules, both concurrency-control paths, reservation state machine, event-publish calls |
| wallet-service | `WalletControllerTest` | 14 | Request validation + HTTP/error-code mapping, all 7 endpoints, incl. missing-header/key-in-progress |
| wallet-service | `IdempotencyGuardTest` | 9 | checkAndReserve/confirm/release + runIdempotent's fresh/replay/failure-releases outcomes |
| wallet-service | `WalletEventPublisherTest` | 4 | Topic/key/payload per event type, failed-send doesn't propagate |
| fx-rate-service | `FxRateCacheTest` | 3 | Snapshot get/refresh/replace |
| fx-rate-service | `DistributedLockManagerTest` | 5 | Acquire/release/lease-expiry/wrong-id-can't-steal-lock |
| fx-rate-service | `RateRefreshSchedulerTest` | 3 | Cache seeding, persisted-row count, bounded random walk, malformed config fails fast |
| fx-rate-service | `FxRateServiceTest` | 17 | Full rate-lock state machine, including the consume-vs-release expiry asymmetry, event-publish calls |
| fx-rate-service | `FxRateControllerTest` | 12 | Request validation + HTTP/error-code mapping, all 4 endpoints, incl. missing-header/key-in-progress |
| fx-rate-service | `IdempotencyGuardTest` | 8 | Identical structure to wallet-service's - the two `IdempotencyGuard` classes are deliberate copies |
| fx-rate-service | `FxRateEventPublisherTest` | 3 | Identical structure to wallet-service's - the two publisher classes are deliberate copies |
| conversion-orchestrator | `SagaStateMachineTest` | 39 | Every valid transition (parameterized, incl. the merchant-payment paths) + every rejected-transition case (skip a step, re-delivered terminal event, backwards move, terminal-state jump) |
| conversion-orchestrator | `ConversionServiceTest` | 14 | One test per saga path: happy path (incl. the 4-leg ledger posting), consume-lock-fails-but-completes, ledger-posting-fails-but-completes, rate-lock-fails, debit-fails (incl. no ledger reversal posted), credit-fails (incl. 2-leg ledger reversal), reversal-itself-fails-stays-stuck, merchant payment approved/declined (incl. 4-leg ledger reversal)/call-fails, post-charge debit fails (refund then compensate) |
| conversion-orchestrator | `ConversionControllerTest` | 5 | Request validation + HTTP/error-code mapping, both endpoints |
| conversion-orchestrator | `IdempotencyGuardTest` | 7 | Identical structure to the other two services' own - third deliberate copy |
| merchant-payment-service | `MerchantPaymentServiceTest` | 9 | Both charge outcomes + event-publish calls, duplicate-transactionId conflict (no event on a failed save), full refund state machine |
| merchant-payment-service | `MerchantPaymentControllerTest` | 8 | Request validation + HTTP/error-code mapping, all 3 endpoints - incl. a declined charge still returning 201 |
| merchant-payment-service | `IdempotencyGuardTest` | 7 | Identical structure to the other services' own - fourth deliberate copy |
| merchant-payment-service | `MerchantPaymentEventPublisherTest` | 3 | Identical structure to the other services' own event-publisher tests |
| ledger-service | `DoubleEntryValidatorTest` | 6 | Pure unit tests, no mocks - balanced pair, empty posting, debit-only, mismatched amounts, multi-leg netting within one currency, the documented cross-currency limitation |
| ledger-service | `LedgerServiceTest` | 4 | Repository mocked, real `DoubleEntryValidator` wired in - balanced posting, `LedgerConflictException` short-circuit, unbalanced posting never reaching `save`, `getStatement` delegation |
| ledger-service | `LedgerControllerTest` | 7 | Request validation + HTTP/error-code mapping, both endpoints - incl. empty `entries`, `INVALID_LEDGER_ENTRIES`, `LEDGER_CONFLICT`, populated and empty statement |
| ledger-service | `IdempotencyGuardTest` | 7 | Identical structure to the other services' own - fifth deliberate copy |
| wallet-service | `WalletRepositoryIntegrationTest` | 3 | Pattern 6 - real Postgres container, Flyway-migrated. `createdAt`/`updatedAt` populated on `save()`'s return, `uk_wallet_user_currency` really enforced, `findById` precision round-trip |
| fx-rate-service | `FxRateLockRepositoryIntegrationTest` | 3 | Pattern 6 - `createdAt` populated, `uk_fx_rate_lock_transaction_id` really enforced, `lockedRate` precision round-trip |
| conversion-orchestrator | `ConversionTransactionRepositoryIntegrationTest` | 3 | Pattern 6 - the direct regression test for the original `Persistable`/merge-vs-persist bug (see above), `uk_conversion_transaction_idempotency_key` really enforced, `sagaState` round-trip |
| merchant-payment-service | `MerchantPaymentRepositoryIntegrationTest` | 3 | Pattern 6 - `createdAt`/`updatedAt` populated, `uk_merchant_payment_transaction_id` really enforced, `status` round-trip |
| ledger-service | `LedgerEntryRepositoryIntegrationTest` | 3 | Pattern 6 - `createdAt` populated, the direct regression test for Bug 3's `VARCHAR(64)` column width (see above), `findByWalletIdOrderByCreatedAtAsc` |

**234 tests in this table** (236 total per `./mvnw test` across all five modules, including 2
pre-existing Spring-Initializr smoke tests not written as part of this work - wallet-service and
fx-rate-service only, the other three modules were hand-scaffolded without one). Run per
service: `cd backend/<service> && ./mvnw test` - the 5 Pattern 6 integration tests need Docker
running locally (Testcontainers spins up a real `postgres:16-alpine` container per test class);
everything else runs with no external dependency at all.

## Checklist for testing the next service

1. `./mvnw dependency:tree` first — confirm what's already on the test classpath before adding
   any dependency.
2. One `@ExtendWith(MockitoExtension.class)` test class per `@Service`, mocking only the
   collaborators that have real (DB/I/O) behavior behind them — use real instances for anything
   simple enough to construct directly (Pattern 3).
3. One `@WebMvcTest` test class per `@RestController`, `@MockitoBean` for the service — walk the
   endpoint's own API doc's error table and write one test per row.
4. Watch for the two mocking traps above — shared `@BeforeEach` stubs need `lenient()` unless
   every test uses them; mutated entities need a fresh object per mock call, not a shared one.
5. If the service uses `TransactionTemplate` instead of `@Transactional` (check for
   self-invocation first), mock `PlatformTransactionManager.getTransaction(...)` to return a
   bare `TransactionStatus` mock.
6. If the service uses `IdempotencyGuard` (or writes a new Redis-backed component), mock
   `StringRedisTemplate` + its `ValueOperations` (Pattern 4) — don't forget `opsForValue()`
   itself needs stubbing before `get`/`set`/`setIfAbsent` on it will do anything.
7. If the service publishes Kafka events, mock `KafkaTemplate<String, String>` (Pattern 5) —
   remember `send(...)` returns a `CompletableFuture` your publisher likely chains
   `.whenComplete(...)` onto, so the stub needs to return an actual future, and write the
   failed-future test proving a broker outage never propagates out of the publisher.
8. If a new entity uses an application-assigned id (not `@GeneratedValue`) and has no
   `@Version` field, implement `Persistable<ID>` on it (see conversion-orchestrator's
   `ConversionTransaction`) - otherwise Spring Data JPA's default new-vs-existing check can
   route even the first `save()` through `merge()` instead of `persist()`, silently dropping any
   `@PrePersist`-set fields from the object you already hold. No unit test with a mocked
   repository will ever catch this - only a real database will. Applied from the start in
   merchant-payment-service's `MerchantPayment` (same shape, same risk) - confirmed clean on the
   first manual `curl` test, no repeat of the bug.
9. Add a Pattern 6 repository integration test (real Postgres via Testcontainers) for the
   service's main entity/repository — copy an existing one (they're all near-identical) and
   adapt the entity, constructor, and the one real unique/check constraint it covers. Real
   Redis/Kafka Testcontainers integration tests are still a deliberate gap across all five
   services — worth deciding once, for whichever service takes it on first, rather than
   re-deciding per service.
