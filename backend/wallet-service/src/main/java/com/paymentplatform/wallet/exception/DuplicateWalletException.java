package com.paymentplatform.wallet.exception;

public class DuplicateWalletException extends RuntimeException {

    public DuplicateWalletException(String userId, String currency) {
        super("User %s already has a wallet in %s".formatted(userId, currency));
    }
}
