package com.paymentplatform.fxrate.service;

import com.paymentplatform.fxrate.domain.FxRateLock;
import com.paymentplatform.fxrate.domain.RateLockStatus;
import com.paymentplatform.fxrate.exception.RateLockConflictException;
import com.paymentplatform.fxrate.exception.RateLockNotActiveException;
import com.paymentplatform.fxrate.exception.RateLockNotFoundException;
import com.paymentplatform.fxrate.exception.UnsupportedCurrencyPairException;
import com.paymentplatform.fxrate.repository.FxRateLockRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * All FX rate business logic: serving the current cached rate, and creating/consuming/releasing
 * short-lived rate locks (design doc 6.2.2, 6.3.2).
 */
@Service
public class FxRateService {

    private static final Logger log = LoggerFactory.getLogger(FxRateService.class);
    private static final int MAX_LOCK_ATTEMPTS = 3;
    private static final Duration CRITICAL_SECTION_LEASE = Duration.ofSeconds(5);

    private final FxRateCache cache;
    private final DistributedLockManager lockManager;
    private final FxRateLockRepository lockRepository;
    private final Duration lockTtl;

    public FxRateService(FxRateCache cache, DistributedLockManager lockManager,
                          FxRateLockRepository lockRepository,
                          @Value("${fx.rate.lock.ttl-seconds}") long lockTtlSeconds) {
        this.cache = cache;
        this.lockManager = lockManager;
        this.lockRepository = lockRepository;
        this.lockTtl = Duration.ofSeconds(lockTtlSeconds);
    }

    public FxRateCache.RateSnapshot getCurrentRate(String base, String quote) {
        return cache.get(base, quote)
                .orElseThrow(() -> new UnsupportedCurrencyPairException(base, quote));
    }

    /**
     * Creates a new ACTIVE rate lock for {@code transactionId} off the current cached rate.
     *
     * The distributed lock (real Redisson RLock per the design doc, in-memory placeholder here
     * - see {@link DistributedLockManager}) is held only for this method's own critical section
     * - read the current rate, build the row, save it - not for the lock's full {@code lockTtl}
     * lifetime. Its job is to serialize concurrent lock-creation attempts for the same pair
     * against each other, not to gate how long the resulting business-level lock is valid.
     */
    public FxRateLock lockRate(String transactionId, String base, String quote, BigDecimal amount) {
        String pairKey = FxRateCache.key(base, quote);

        for (int attempt = 1; attempt <= MAX_LOCK_ATTEMPTS; attempt++) {
            Optional<String> acquired = lockManager.acquireLock(pairKey, CRITICAL_SECTION_LEASE);
            if (acquired.isEmpty()) {
                log.info("Pair {} lock-creation mutex busy (attempt {}/{}), retrying", pairKey, attempt, MAX_LOCK_ATTEMPTS);
                sleepBackoff(5L * attempt);
                continue;
            }
            String mutexId = acquired.get();
            try {
                FxRateCache.RateSnapshot current = getCurrentRate(base, quote);
                FxRateLock lock = new FxRateLock(
                        UUID.randomUUID().toString(), transactionId, base, quote,
                        current.rate(), amount, RateLockStatus.ACTIVE, Instant.now().plus(lockTtl));
                try {
                    return lockRepository.save(lock);
                } catch (DataIntegrityViolationException ex) {
                    // transaction_id UNIQUE - a duplicate/retried lockRate call for a
                    // transaction that already has one lands here.
                    throw new RateLockConflictException(
                            "A rate lock already exists for transaction " + transactionId);
                }
            } finally {
                lockManager.releaseLock(pairKey, mutexId);
            }
        }
        throw new RateLockConflictException(
                "Could not acquire the rate-lock mutex for %s after %d attempts".formatted(pairKey, MAX_LOCK_ATTEMPTS));
    }

    /** Marks a lock CONSUMED - the locked rate was actually used to complete a conversion step. */
    public FxRateLock consumeLock(String lockId) {
        FxRateLock lock = requireActiveLock(lockId);
        lock.setStatus(RateLockStatus.CONSUMED);
        return lockRepository.save(lock);
    }

    /**
     * Releases a lock. Idempotent by design (design doc 6.4): releasing an already-RELEASED or
     * already-EXPIRED lock is a no-op success, not an error - a saga compensation step or a
     * retried release call should never fail just because it already happened. Releasing a
     * CONSUMED lock is rejected: the rate was already used, there is nothing left to give back.
     */
    public FxRateLock releaseLock(String lockId) {
        FxRateLock lock = lockRepository.findById(lockId)
                .orElseThrow(() -> new RateLockNotFoundException(lockId));
        if (lock.getStatus() == RateLockStatus.RELEASED || lock.getStatus() == RateLockStatus.EXPIRED) {
            return lock;
        }
        if (lock.getStatus() == RateLockStatus.CONSUMED) {
            throw new RateLockNotActiveException(lockId, lock.getStatus());
        }
        lock.setStatus(isExpired(lock) ? RateLockStatus.EXPIRED : RateLockStatus.RELEASED);
        return lockRepository.save(lock);
    }

    private FxRateLock requireActiveLock(String lockId) {
        FxRateLock lock = lockRepository.findById(lockId)
                .orElseThrow(() -> new RateLockNotFoundException(lockId));
        if (lock.getStatus() != RateLockStatus.ACTIVE) {
            throw new RateLockNotActiveException(lockId, lock.getStatus());
        }
        if (isExpired(lock)) {
            lock.setStatus(RateLockStatus.EXPIRED);
            lockRepository.save(lock);
            throw new RateLockNotActiveException(lockId, RateLockStatus.EXPIRED);
        }
        return lock;
    }

    private boolean isExpired(FxRateLock lock) {
        return lock.getExpiresAt().isBefore(Instant.now());
    }

    private void sleepBackoff(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while retrying a rate-lock request", e);
        }
    }
}
