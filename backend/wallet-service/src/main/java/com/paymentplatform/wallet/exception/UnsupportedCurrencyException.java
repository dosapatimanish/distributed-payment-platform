package com.paymentplatform.wallet.exception;

/** The requested currency has no active row in the {@code currency} table. */
public class UnsupportedCurrencyException extends RuntimeException {

    public UnsupportedCurrencyException(String code) {
        super("Currency " + code + " is not supported");
    }
}
