package com.paymentplatform.fxrate.exception;

import com.paymentplatform.fxrate.domain.RateLockStatus;

/** Thrown when consuming, or releasing an already-consumed, rate lock that is not (or is no longer) ACTIVE. */
public class RateLockNotActiveException extends RuntimeException {

    public RateLockNotActiveException(String lockId, RateLockStatus actual) {
        super("Rate lock %s is %s, not ACTIVE".formatted(lockId, actual));
    }
}
