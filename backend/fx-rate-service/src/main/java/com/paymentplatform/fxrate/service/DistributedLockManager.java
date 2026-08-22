package com.paymentplatform.fxrate.service;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory stand-in for the Redisson {@code RLock} the design doc calls for (6.2.2, 6.3.2).
 * A single-instance FX Rate Service has no cross-JVM lock contention to solve yet, so this uses
 * a {@code ConcurrentHashMap<String, LockEntry>} keyed by currency pair, each entry carrying
 * its own lease-expiry - close enough to prove out the "one holder per key, auto-expiring, no
 * forever-stuck lock if the holder dies" contract this service is actually built against.
 *
 * Deliberately deferred, not an oversight (same category as wallet-service's deferred Kafka/
 * Redis-idempotency pieces): swapping in a real Redisson {@code RLock} (lease time + watchdog
 * auto-renewal, safe across multiple service instances) is a change to this class's body only -
 * {@link FxRateService} depends solely on the two method signatures below.
 */
@Component
public class DistributedLockManager {

    private record LockEntry(String lockId, Instant expiresAt) {
    }

    private final Map<String, LockEntry> locks = new ConcurrentHashMap<>();

    /** Returns the acquired lock's id, or empty if another holder currently has this key. */
    public synchronized Optional<String> acquireLock(String key, Duration lease) {
        LockEntry existing = locks.get(key);
        if (existing != null && existing.expiresAt().isAfter(Instant.now())) {
            return Optional.empty();
        }
        String lockId = UUID.randomUUID().toString();
        locks.put(key, new LockEntry(lockId, Instant.now().plus(lease)));
        return Optional.of(lockId);
    }

    /** No-op (not an error) if this lockId no longer holds the key - already expired or already released. */
    public synchronized void releaseLock(String key, String lockId) {
        LockEntry existing = locks.get(key);
        if (existing != null && existing.lockId().equals(lockId)) {
            locks.remove(key);
        }
    }
}
