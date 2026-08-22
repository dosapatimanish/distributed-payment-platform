package com.paymentplatform.wallet.exception;

/**
 * Thrown when the optimistic-locking retry loop in WalletService exhausts its attempts -
 * i.e. this wallet is under contention heavy enough that we couldn't win the race in a
 * bounded number of tries. The caller should retry the whole request.
 */
public class WalletConflictException extends RuntimeException {

    public WalletConflictException(String walletId, int attempts) {
        super("Wallet %s is under heavy contention: gave up after %d attempts".formatted(walletId, attempts));
    }
}
