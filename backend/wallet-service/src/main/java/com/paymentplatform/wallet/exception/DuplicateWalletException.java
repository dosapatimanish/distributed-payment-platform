package com.paymentplatform.wallet.exception;

public class DuplicateWalletException extends RuntimeException {

    public DuplicateWalletException(String cif, String currency) {
        super("CIF %s already has a wallet in %s".formatted(cif, currency));
    }
}
