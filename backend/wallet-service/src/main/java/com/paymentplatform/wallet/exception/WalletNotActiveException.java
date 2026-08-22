package com.paymentplatform.wallet.exception;

import com.paymentplatform.wallet.domain.WalletStatus;

public class WalletNotActiveException extends RuntimeException {

    public WalletNotActiveException(String walletId, WalletStatus status) {
        super("Wallet %s is not active (status=%s)".formatted(walletId, status));
    }
}
