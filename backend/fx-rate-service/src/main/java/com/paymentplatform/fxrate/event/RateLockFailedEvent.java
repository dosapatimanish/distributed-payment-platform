package com.paymentplatform.fxrate.event;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Published to {@code rate.lock.failed} (design doc 6.5) when a lockRate attempt fails for any
 * reason (unsupported pair, or the lock-creation mutex/unique-constraint conflict) - the
 * orchestrator's cue that this step of the saga did not get a locked rate.
 */
public record RateLockFailedEvent(
        String transactionId,
        String baseCurrency,
        String quoteCurrency,
        BigDecimal amount,
        String reason,
        Instant occurredAt
) {
}
