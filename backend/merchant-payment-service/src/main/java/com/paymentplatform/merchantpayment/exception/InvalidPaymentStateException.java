package com.paymentplatform.merchantpayment.exception;

import com.paymentplatform.merchantpayment.domain.PaymentStatus;

/** Thrown when refunding a payment that is not currently COMPLETED (never charged, or already refunded is handled separately as a no-op - see MerchantPaymentService.refund). */
public class InvalidPaymentStateException extends RuntimeException {

    public InvalidPaymentStateException(String paymentId, PaymentStatus actual) {
        super("Payment %s is %s, cannot be refunded".formatted(paymentId, actual));
    }
}
