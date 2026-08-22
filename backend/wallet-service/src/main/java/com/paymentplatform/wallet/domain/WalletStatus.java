package com.paymentplatform.wallet.domain;

/**
 * Lifecycle status of a wallet. A wallet only accepts debits while ACTIVE;
 * FROZEN blocks debits (e.g. compliance hold) but still allows credits;
 * CLOSED blocks everything.
 */
public enum WalletStatus {
    ACTIVE,
    FROZEN,
    CLOSED
}
