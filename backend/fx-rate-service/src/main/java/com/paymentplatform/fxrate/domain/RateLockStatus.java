package com.paymentplatform.fxrate.domain;

/**
 * Lifecycle status of a rate lock (design doc 6.1.2).
 * ACTIVE -> CONSUMED (the locked rate was actually used to complete a conversion)
 * ACTIVE -> RELEASED (the caller gave it up without using it - e.g. saga compensation)
 * ACTIVE -> EXPIRED (nobody consumed or released it before {@code expiresAt})
 * CONSUMED and RELEASED are terminal. EXPIRED is terminal in practice (a lock is not
 * reactivated after its TTL passes), even though nothing currently sweeps for it - see
 * FxRateService.
 */
public enum RateLockStatus {
    ACTIVE,
    CONSUMED,
    RELEASED,
    EXPIRED
}
