package com.paymentplatform.merchantpayment.exception;

public class PaymentNotFoundException extends RuntimeException {

    public PaymentNotFoundException(String paymentId) {
        super("Merchant payment not found: " + paymentId);
    }
}
