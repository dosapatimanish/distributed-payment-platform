package com.paymentplatform.merchantpayment.exception;

/** Thrown when a payment already exists for this transactionId (its UNIQUE constraint) - a duplicate/retried charge attempt with a different Idempotency-Key. */
public class PaymentConflictException extends RuntimeException {

    public PaymentConflictException(String transactionId) {
        super("A payment already exists for transaction " + transactionId);
    }
}
