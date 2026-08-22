package com.paymentplatform.fxrate.service;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class DistributedLockManagerTest {

    private final DistributedLockManager lockManager = new DistributedLockManager();

    @Test
    void acquireLock_freeKey_returnsLockId() {
        Optional<String> lockId = lockManager.acquireLock("USD/INR", Duration.ofSeconds(5));

        assertThat(lockId).isPresent();
    }

    @Test
    void acquireLock_alreadyHeld_returnsEmpty() {
        lockManager.acquireLock("USD/INR", Duration.ofSeconds(5));

        Optional<String> second = lockManager.acquireLock("USD/INR", Duration.ofSeconds(5));

        assertThat(second).isEmpty();
    }

    @Test
    void releaseLock_thenAcquireAgain_succeeds() {
        String lockId = lockManager.acquireLock("USD/INR", Duration.ofSeconds(5)).orElseThrow();

        lockManager.releaseLock("USD/INR", lockId);

        assertThat(lockManager.acquireLock("USD/INR", Duration.ofSeconds(5))).isPresent();
    }

    @Test
    void releaseLock_withWrongLockId_isNoOp_doesNotFreeTheRealHolder() {
        String realLockId = lockManager.acquireLock("USD/INR", Duration.ofSeconds(5)).orElseThrow();

        lockManager.releaseLock("USD/INR", "some-other-lock-id");

        // The real holder's lock is untouched - a stale/wrong release must not free someone
        // else's active lock.
        assertThat(lockManager.acquireLock("USD/INR", Duration.ofSeconds(5))).isEmpty();
        assertThat(realLockId).isNotBlank();
    }

    @Test
    void acquireLock_afterLeaseExpires_succeedsWithoutExplicitRelease() throws InterruptedException {
        lockManager.acquireLock("USD/INR", Duration.ofMillis(30));

        Thread.sleep(60);

        assertThat(lockManager.acquireLock("USD/INR", Duration.ofSeconds(5))).isPresent();
    }
}
