package com.paymentplatform.wallet.exception;

/** Wrong email or password at sign-in. Deliberately does not say which. */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        super("Invalid email or password");
    }
}
