package com.paymentplatform.wallet.exception;

import java.math.BigDecimal;

public class InsufficientFundsException extends RuntimeException {

    public InsufficientFundsException(String walletId, BigDecimal requested, BigDecimal available) {
        super("Wallet %s has insufficient funds: requested %s, available %s"
                .formatted(walletId, requested, available));
    }
}
