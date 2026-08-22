# Kafka Event Publishing — Concept, Rationale, and Implementation

Cross-cutting concept doc, not tied to one service — wallet-service and fx-rate-service both
implement this the same way (deliberate independent copies of the same publisher pattern, not a
shared library — see each service's implementation-notes doc, same approach as `idempotency.md`).

## What this is

Both services now publish domain events to Kafka whenever a money-relevant thing happens:
wallet-service on every debit/credit, fx-rate-service on every rate-lock attempt. This is
**producer-only** for this pass — no consumer exists yet, because the thing that would consume
these (the Conversion Orchestrator) hasn't been built. Publishing now, ahead of having a
consumer, is deliberate: it proves the producer side works in isolation and gives the
orchestrator something real to build against later, rather than building both sides blind.

## Why this matters here specifically

This platform is architected as an orchestration-based SAGA (design doc §3.3) precisely because
a currency-conversion-and-payment operation spans databases owned by different services — no
single ACID transaction can cover "debit the source wallet, lock a rate, credit the destination
wallet, pay the merchant." The orchestrator drives that multi-step process by reacting to
**events each step publishes**: it doesn't call wallet-service and fx-rate-service synchronously
and block on the response for the whole saga — it issues a command, then reacts to whichever
event comes back (`wallet.debited` vs `wallet.debit.failed`) to decide the next step or trigger
compensation.

Without published events, there is no way for a separate orchestrator process to know a debit
succeeded, failed, or how much it moved the balance — the debit's own HTTP response only reaches
the caller that made the request, not any other interested service.

## Topics (design doc §6.5)

| Topic | Publisher | Partition key | Published from |
|---|---|---|---|
| `wallet.debited` | wallet-service | `walletId` | `WalletService.debit` — success |
| `wallet.debit.failed` | wallet-service | `walletId` | `WalletService.debit` — any failure (not found, not active, insufficient funds, conflict) |
| `wallet.credited` | wallet-service | `walletId` | `WalletService.credit` — success only, no failure topic exists for credit |
| `rate.locked` | fx-rate-service | `transactionId` | `FxRateService.lockRate` — success |
| `rate.lock.failed` | fx-rate-service | `transactionId` | `FxRateService.lockRate` — any failure (unsupported pair, conflict) |

Two topics from the design doc's full table are **not** built yet — `payment.completed` /
`payment.failed` (Merchant Payment Service, doesn't exist) and `saga.completed` /
`saga.compensated` (Conversion Orchestrator, doesn't exist). Those are the next services' jobs
to publish, not these two.

**Why the partition key matters**: Kafka guarantees ordering only *within* a partition, and a
topic's messages are spread across partitions by key. Keying `wallet.*` events by `walletId`
means every event for one wallet always lands on the same partition, so a consumer processing
that partition sees them in the order they actually happened — critical, because a consumer
that saw `wallet.credited` before an earlier `wallet.debited` for the same wallet would build an
inconsistent picture of that wallet's history. Same reasoning for `transactionId` on the
`rate.*` topics — one conversion's events stay ordered relative to each other. See design doc
§6.2.4 for the platform-wide statement of this principle (it also covers the *consumer* side,
`@KafkaListener` concurrency matching partition count — not relevant yet since there's no
consumer).

## How it's implemented

### Publisher classes

`WalletEventPublisher` / `FxRateEventPublisher` (`com.paymentplatform.wallet.event` /
`com.paymentplatform.fxrate.event`), each wrapping a `KafkaTemplate<String, String>`:

```java
@Component
public class WalletEventPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public void publishDebited(WalletDebitedEvent event) {
        send("wallet.debited", event.walletId(), event);
    }

    private void send(String topic, String key, Object event) {
        String payload = objectMapper.writeValueAsString(event);
        kafkaTemplate.send(topic, key, payload)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.warn("Failed to publish to topic {} (key={}): {}", topic, key, ex.getMessage());
                    }
                });
    }
}
```

Each event is a plain record (`WalletDebitedEvent`, `WalletDebitFailedEvent`,
`WalletCreditedEvent`, `RateLockedEvent`, `RateLockFailedEvent`) — no shared base type; each
topic gets exactly the fields it needs.

### Where it's called from

Directly inside the service method, right after the mutation that produced the event has
already committed — e.g. `WalletService.debit` wraps its existing `applyMutation` call in a
try/catch and publishes on the way out either side:

```java
public Wallet debit(String walletId, BigDecimal amount, String transactionId) {
    try {
        Wallet result = applyMutation(walletId, wallet -> debitMutation(wallet, amount));
        eventPublisher.publishDebited(new WalletDebitedEvent(walletId, transactionId, amount, result.getBalance(), Instant.now()));
        return result;
    } catch (RuntimeException ex) {
        eventPublisher.publishDebitFailed(new WalletDebitFailedEvent(walletId, transactionId, amount, ex.getMessage(), Instant.now()));
        throw ex;
    }
}
```

`FxRateService.lockRate` uses the identical shape: the original method body was renamed to a
private `doLockRate`, and the public `lockRate` wraps it in the same try/publish-then-rethrow
pattern.

### Why plain strings, not spring-kafka's `JsonSerializer`

Spring Kafka's built-in `org.springframework.kafka.support.serializer.JsonSerializer` still
imports `com.fasterxml.jackson.databind.ObjectMapper` — **Jackson 2** — as of spring-kafka
4.1.1. This project runs **Jackson 3** (`tools.jackson.databind.ObjectMapper`, pulled in by
`spring-boot-starter-webmvc` — see the Boot 4.1 gotchas in each service's implementation-notes
doc). Rather than pull in a second, older Jackson major version just to satisfy spring-kafka's
serializer, both services configure plain `StringSerializer` for the Kafka value type and do the
JSON conversion themselves inside the publisher, using the app's own Jackson-3 `ObjectMapper`
bean — the exact same pattern `IdempotencyGuard` already uses for Redis. One technique, reused
for a second infrastructure integration.

### Async send, failures logged not thrown

`kafkaTemplate.send(...)` returns a `CompletableFuture`; both publishers attach a
`.whenComplete(...)` that logs a warning on failure and does nothing else — it never blocks the
calling thread and never turns a Kafka outage into an HTTP failure for a request whose database
work already committed successfully. A debit still returns `200` to its caller even if Kafka is
completely unreachable at that moment.

### Deliberate simplification: no outbox, no delivery guarantee

This is a direct publish, not a Transactional Outbox. The gap this leaves: **if the process
crashes in the narrow window between the DB transaction committing and the Kafka send actually
completing, the event is lost even though the underlying mutation genuinely happened.** No retry,
no replay, nothing downstream will ever know that debit occurred.

This is an accepted, documented gap for this pass — not an oversight. The wallet/fx-rate design
doc explicitly separates "Kafka event publishing" and "Transactional Outbox pattern" as two
different deferred items; this work closes the first, not the second. Closing the second means:
writing the event to a local `outbox_event` table in the *same* DB transaction as the mutation
(so they succeed or fail atomically), then a separate relay process/poller actually publishes to
Kafka from that table and marks rows sent — turning "publish, hope it lands" into "publish,
guaranteed eventually." Worth doing before any real production reliance on these events; not
worth doing before a consumer exists to care.

## Testing without a real Kafka broker

Unit-tested by mocking `KafkaTemplate<String, String>` (Mockito) — same idea as mocking any
other collaborator (`testing-guide.md` Pattern 1), with a real `ObjectMapper` for the
serialization (Pattern 3). `WalletEventPublisherTest` / `FxRateEventPublisherTest` verify each
publish method sends to the right topic, keyed correctly, with a payload containing the expected
fields — plus one test per class proving a failed/exceptional `CompletableFuture` from
`kafkaTemplate.send(...)` never propagates out of the publisher.

`WalletServiceTest` / `FxRateServiceTest` separately verify the *calling* side: a successful
debit/lockRate calls the success-publish method and never the failure one, and vice versa.

This proves the publishing logic is correct given whatever `KafkaTemplate` does — it does not
prove events actually arrive at a real broker, survive a restart, or preserve partition ordering
under real concurrent load. That was verified once, manually, against a real broker (see below);
it is not covered by the automated suite, which is a Testcontainers-shaped gap same as the
Postgres/Redis ones already tracked in `testing-guide.md`.

## Manually verified (against a real broker)

`docker compose up -d kafka` (single-node KRaft mode, `apache/kafka:3.9.0` — no Zookeeper
needed), both services started against it, then:

- Debit success → `wallet.debited`, keyed by `walletId`, correct payload.
- Debit failure (insufficient funds) → `wallet.debit.failed`, same key, `reason` field carries
  the exception message.
- Credit success → `wallet.credited`.
- Rate lock success → `rate.locked`, keyed by `transactionId`, `lockedRate` matches the response.
- Rate lock on an unsupported pair → `rate.lock.failed`.

Read back with `kafka-console-consumer.sh --from-beginning --property print.key=true` inside
the `platform-kafka` container — confirmed the message key really is the walletId/transactionId,
not just the payload field of the same name.

## Related docs

- `wallet-service-implementation.md` / `fx-rate-service-implementation.md` — the "Kafka Events"
  section in each, service-specific detail.
- `idempotency.md` — the other cross-cutting infrastructure concern added the same way
  (independent copies of one pattern per service), same shared-Redis-vs-shared-Kafka reasoning.
- `testing-guide.md` — the mocking pattern used in both `*EventPublisherTest` classes.
