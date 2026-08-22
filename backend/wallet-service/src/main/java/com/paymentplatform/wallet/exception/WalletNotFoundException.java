package com.paymentplatform.wallet.exception;

public class WalletNotFoundException extends RuntimeException {

    public WalletNotFoundException(String walletId) {
        super("Wallet not found: " + walletId);
    }
}
