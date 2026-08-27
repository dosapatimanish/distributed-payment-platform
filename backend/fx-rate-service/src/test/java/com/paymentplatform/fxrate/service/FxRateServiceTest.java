package com.paymentplatform.fxrate.service;

import com.paymentplatform.fxrate.domain.FxRateLock;
import com.paymentplatform.fxrate.domain.RateLockStatus;
import com.paymentplatform.fxrate.event.FxRateEventPublisher;
import com.paymentplatform.fxrate.exception.RateLockConflictException;
import com.paymentplatform.fxrate.exception.RateLockNotActiveException;
import com.paymentplatform.fxrate.exception.RateLockNotFoundException;
import com.paymentplatform.fxrate.exception.UnsupportedCurrencyPairException;
import com.paymentplatform.fxrate.repository.FxRateLockRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for FxRateService. {@link FxRateCache} and {@link DistributedLockManager} are used
 * as real instances (both are simple, in-memory, no Spring/DB dependency) - only the JPA
 * repository is mocked.
 */
@ExtendWith(MockitoExtension.class)
class FxRateServiceTest {

    private static final long LOCK_TTL_SECONDS = 10;

    @Mock
    private FxRateLockRepository lockRepository;

    @Mock
    private FxRateEventPublisher eventPublisher;

    @Mock
    private SequenceIds sequenceIds;

    private FxRateCache cache;
    private SimpleMeterRegistry meterRegistry;
    private FxRateService fxRateService;

    @BeforeEach
    void setUp() {
        cache = new FxRateCache();
        meterRegistry = new SimpleMeterRegistry();
        org.mockito.Mockito.lenient()
                .when(sequenceIds.next(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn("LK0000000001");
        fxRateService = new FxRateService(cache, new DistributedLockManager(), lockRepository, eventPublisher,
                sequenceIds, LOCK_TTL_SECONDS, meterRegistry);
    }

    private void seedRate(String base, String quote, String rate) {
        cache.refresh(Map.of(FxRateCache.key(base, quote),
                new FxRateCache.RateSnapshot(new BigDecimal(rate), "SIMULATED_FEED", Instant.now())));
    }

    // ------------------------------------------------------------------
    // getCurrentRate
    // ------------------------------------------------------------------

    @Test
    void getCurrentRate_cachedPair_returnsSnapshot() {
        seedRate("USD", "INR", "83.0000");

        FxRateCache.RateSnapshot result = fxRateService.getCurrentRate("USD", "INR");

        assertThat(result.rate()).isEqualByComparingTo("83.0000");
    }

    @Test
    void getCurrentRate_uncachedPair_throws() {
        assertThatThrownBy(() -> fxRateService.getCurrentRate("XXX", "YYY"))
                .isInstanceOf(UnsupportedCurrencyPairException.class);
    }

    // ------------------------------------------------------------------
    // lockRate
    // ------------------------------------------------------------------

    @Test
    void lockRate_cachedPair_createsActiveLockAtCurrentRate() {
        seedRate("USD", "INR", "83.0000");
        when(lockRepository.save(any(FxRateLock.class))).thenAnswer(inv -> inv.getArgument(0));

        FxRateLock lock = fxRateService.lockRate("txn-1", "USD", "INR", new BigDecimal("100.00"));

        assertThat(lock.getStatus()).isEqualTo(RateLockStatus.ACTIVE);
        assertThat(lock.getLockedRate()).isEqualByComparingTo("83.0000");
        assertThat(lock.getTransactionId()).isEqualTo("txn-1");
        assertThat(lock.getExpiresAt()).isAfter(Instant.now());
        // design doc 5.4's lock-wait time metric - recorded on this (successful) outcome too.
        assertThat(meterRegistry.timer("fxrate.lock.wait.time").count()).isEqualTo(1);
    }

    @Test
    void lockRate_uncachedPair_throwsAndReleasesMutex() {
        assertThatThrownBy(() -> fxRateService.lockRate("txn-1", "XXX", "YYY", BigDecimal.TEN))
                .isInstanceOf(UnsupportedCurrencyPairException.class);

        // The per-pair mutex must be released even on failure (finally block) - a second lock
        // attempt for the same pair right after must not be blocked by the first's mutex.
        seedRate("XXX", "YYY", "1.0000");
        when(lockRepository.save(any(FxRateLock.class))).thenAnswer(inv -> inv.getArgument(0));
        assertThat(fxRateService.lockRate("txn-2", "XXX", "YYY", BigDecimal.TEN).getStatus())
                .isEqualTo(RateLockStatus.ACTIVE);
    }

    @Test
    void lockRate_duplicateTransactionId_throwsConflict() {
        seedRate("USD", "INR", "83.0000");
        when(lockRepository.save(any(FxRateLock.class))).thenThrow(new DataIntegrityViolationException("duplicate"));

        assertThatThrownBy(() -> fxRateService.lockRate("txn-1", "USD", "INR", BigDecimal.TEN))
                .isInstanceOf(RateLockConflictException.class);
    }

    @Test
    void lockRate_success_publishesRateLockedEvent() {
        seedRate("USD", "INR", "83.0000");
        when(lockRepository.save(any(FxRateLock.class))).thenAnswer(inv -> inv.getArgument(0));

        fxRateService.lockRate("txn-1", "USD", "INR", new BigDecimal("100.00"));

        verify(eventPublisher).publishRateLocked(any());
        verify(eventPublisher, never()).publishRateLockFailed(any());
    }

    @Test
    void lockRate_uncachedPair_publishesRateLockFailedEvent() {
        assertThatThrownBy(() -> fxRateService.lockRate("txn-1", "XXX", "YYY", BigDecimal.TEN))
                .isInstanceOf(UnsupportedCurrencyPairException.class);

        verify(eventPublisher).publishRateLockFailed(any());
        verify(eventPublisher, never()).publishRateLocked(any());
    }

    // ------------------------------------------------------------------
    // consumeLock
    // ------------------------------------------------------------------

    @Test
    void consumeLock_activeLock_marksConsumed() {
        FxRateLock lock = activeLock("lock-1", Instant.now().plusSeconds(60));
        when(lockRepository.findById("lock-1")).thenReturn(Optional.of(lock));
        when(lockRepository.save(any(FxRateLock.class))).thenAnswer(inv -> inv.getArgument(0));

        FxRateLock result = fxRateService.consumeLock("lock-1");

        assertThat(result.getStatus()).isEqualTo(RateLockStatus.CONSUMED);
    }

    @Test
    void consumeLock_notFound_throws() {
        when(lockRepository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> fxRateService.consumeLock("missing"))
                .isInstanceOf(RateLockNotFoundException.class);
    }

    @Test
    void consumeLock_alreadyConsumed_throwsNotActive() {
        FxRateLock lock = activeLock("lock-1", Instant.now().plusSeconds(60));
        lock.setStatus(RateLockStatus.CONSUMED);
        when(lockRepository.findById("lock-1")).thenReturn(Optional.of(lock));

        assertThatThrownBy(() -> fxRateService.consumeLock("lock-1"))
                .isInstanceOf(RateLockNotActiveException.class);
    }

    @Test
    void consumeLock_expiredButStillActive_lazilyExpiresAndThrows() {
        FxRateLock lock = activeLock("lock-1", Instant.now().minusSeconds(1));
        when(lockRepository.findById("lock-1")).thenReturn(Optional.of(lock));
        when(lockRepository.save(any(FxRateLock.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThatThrownBy(() -> fxRateService.consumeLock("lock-1"))
                .isInstanceOf(RateLockNotActiveException.class);
        assertThat(lock.getStatus()).isEqualTo(RateLockStatus.EXPIRED);
    }

    // ------------------------------------------------------------------
    // releaseLock
    // ------------------------------------------------------------------

    @Test
    void releaseLock_activeLock_marksReleased() {
        FxRateLock lock = activeLock("lock-1", Instant.now().plusSeconds(60));
        when(lockRepository.findById("lock-1")).thenReturn(Optional.of(lock));
        when(lockRepository.save(any(FxRateLock.class))).thenAnswer(inv -> inv.getArgument(0));

        FxRateLock result = fxRateService.releaseLock("lock-1");

        assertThat(result.getStatus()).isEqualTo(RateLockStatus.RELEASED);
    }

    @Test
    void releaseLock_alreadyReleased_isIdempotentNoOp() {
        FxRateLock lock = activeLock("lock-1", Instant.now().plusSeconds(60));
        lock.setStatus(RateLockStatus.RELEASED);
        when(lockRepository.findById("lock-1")).thenReturn(Optional.of(lock));

        FxRateLock result = fxRateService.releaseLock("lock-1");

        assertThat(result.getStatus()).isEqualTo(RateLockStatus.RELEASED);
    }

    @Test
    void releaseLock_alreadyExpired_isIdempotentNoOp() {
        FxRateLock lock = activeLock("lock-1", Instant.now().minusSeconds(60));
        lock.setStatus(RateLockStatus.EXPIRED);
        when(lockRepository.findById("lock-1")).thenReturn(Optional.of(lock));

        FxRateLock result = fxRateService.releaseLock("lock-1");

        assertThat(result.getStatus()).isEqualTo(RateLockStatus.EXPIRED);
    }

    @Test
    void releaseLock_consumedLock_throwsNotActive() {
        FxRateLock lock = activeLock("lock-1", Instant.now().plusSeconds(60));
        lock.setStatus(RateLockStatus.CONSUMED);
        when(lockRepository.findById("lock-1")).thenReturn(Optional.of(lock));

        assertThatThrownBy(() -> fxRateService.releaseLock("lock-1"))
                .isInstanceOf(RateLockNotActiveException.class);
    }

    @Test
    void releaseLock_activeButExpired_marksExpiredNotRelease_andSucceeds() {
        // Different from consumeLock: releasing an expired-but-still-ACTIVE lock succeeds
        // (marks it EXPIRED), it doesn't throw - see FxRateService.releaseLock.
        FxRateLock lock = activeLock("lock-1", Instant.now().minusSeconds(1));
        when(lockRepository.findById("lock-1")).thenReturn(Optional.of(lock));
        when(lockRepository.save(any(FxRateLock.class))).thenAnswer(inv -> inv.getArgument(0));

        FxRateLock result = fxRateService.releaseLock("lock-1");

        assertThat(result.getStatus()).isEqualTo(RateLockStatus.EXPIRED);
    }

    @Test
    void releaseLock_notFound_throws() {
        when(lockRepository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> fxRateService.releaseLock("missing"))
                .isInstanceOf(RateLockNotFoundException.class);
    }

    private FxRateLock activeLock(String lockId, Instant expiresAt) {
        return new FxRateLock(lockId, "txn-" + lockId, "USD", "INR",
                new BigDecimal("83.0000"), new BigDecimal("100.00"), RateLockStatus.ACTIVE, expiresAt);
    }
}
