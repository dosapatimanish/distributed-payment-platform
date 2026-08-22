package com.paymentplatform.fxrate.service;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * In-memory current-rate cache (design doc 6.2.2, 6.3.2). Holds one immutable snapshot map,
 * guarded by a {@link ReadWriteLock}: many concurrent readers (every {@code getCurrentRate} /
 * {@code lockRate} call) read the snapshot reference without blocking each other or the
 * refresh thread; {@link RateRefreshScheduler} takes the write lock only for the instant it
 * swaps the whole map in, once per tick. This is the "many readers, rare atomic full swap"
 * pattern the design doc describes - not a per-pair lock, because the scheduler refreshes all
 * tracked pairs together and callers should never see half-old/half-new rates from one tick.
 */
@Component
public class FxRateCache {

    public record RateSnapshot(BigDecimal rate, String source, Instant effectiveAt) {
    }

    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private Map<String, RateSnapshot> snapshot = Map.of();

    public Optional<RateSnapshot> get(String base, String quote) {
        lock.readLock().lock();
        try {
            return Optional.ofNullable(snapshot.get(key(base, quote)));
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Replaces the entire tracked-pairs snapshot atomically. Called once per refresh tick. */
    public void refresh(Map<String, RateSnapshot> newSnapshot) {
        lock.writeLock().lock();
        try {
            this.snapshot = newSnapshot;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public static String key(String base, String quote) {
        return base + "/" + quote;
    }
}
