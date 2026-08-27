package com.paymentplatform.wallet.exception;

public class CurrencyNotFoundException extends RuntimeException {

    public CurrencyNotFoundException(String code) {
        super("No currency with code " + code);
    }
}
