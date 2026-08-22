package com.paymentplatform.orchestrator.exception;

public class ConversionNotFoundException extends RuntimeException {

    public ConversionNotFoundException(String transactionId) {
        super("Conversion not found: " + transactionId);
    }
}
