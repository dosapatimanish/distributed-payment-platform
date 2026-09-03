package com.paymentplatform.wallet.exception;

public class EmailAlreadyRegisteredException extends RuntimeException {

    public EmailAlreadyRegisteredException(String email) {
        super("An account already exists for %s".formatted(email));
    }
}
