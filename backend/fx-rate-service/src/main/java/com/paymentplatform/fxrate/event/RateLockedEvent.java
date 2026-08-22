package com.paymentplatform.fxrate.event;

import java.math.BigDecimal;
import java.time.Instant;

/** Published to {@code rate.locked} (design doc 6.5) after a rate lock actually commits. */
public record RateLockedEvent(
        String transactionId,
        String lockId,
        String baseCurrency,
        String quoteCurrency,
        BigDecimal lockedRate,
        BigDecimal amount,
        Instant occurredAt
) {
}
