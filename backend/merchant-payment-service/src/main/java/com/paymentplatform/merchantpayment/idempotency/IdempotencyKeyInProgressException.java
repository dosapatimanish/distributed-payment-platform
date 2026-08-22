package com.paymentplatform.merchantpayment.idempotency;

/**
 * Thrown when a request arrives carrying an {@code Idempotency-Key} that another, still-running
 * attempt currently holds - see {@link IdempotencyGuard}. The caller should poll rather than
 * resubmit; the in-flight attempt has not finished yet.
 */
public class IdempotencyKeyInProgressException extends RuntimeException {

    public IdempotencyKeyInProgressException(String idempotencyKey) {
        super("A request with Idempotency-Key '%s' is already in progress - poll instead of resubmitting"
                .formatted(idempotencyKey));
    }
}
