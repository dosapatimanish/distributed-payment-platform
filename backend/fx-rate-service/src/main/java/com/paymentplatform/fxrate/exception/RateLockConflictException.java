package com.paymentplatform.fxrate.exception;

/**
 * Thrown when {@code DistributedLockManager} couldn't acquire the short per-pair mutex used to
 * serialize rate-lock creation (design doc 6.2.2), or when a lock already exists for this
 * {@code transactionId} (its UNIQUE constraint). Either way the caller should retry the whole
 * request, same spirit as WalletConflictException in wallet-service.
 */
public class RateLockConflictException extends RuntimeException {

    public RateLockConflictException(String message) {
        super(message);
    }
}
