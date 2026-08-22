package com.paymentplatform.fxrate.exception;

public class RateLockNotFoundException extends RuntimeException {

    public RateLockNotFoundException(String lockId) {
        super("Rate lock not found: " + lockId);
    }
}
