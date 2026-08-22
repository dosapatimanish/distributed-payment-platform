package com.paymentplatform.wallet.domain;

/**
 * Lifecycle status of a wallet reservation (a "hold" against a wallet's balance).
 * HELD -> CAPTURED (funds actually debited) or HELD -> RELEASED (hold freed, no debit).
 * Either terminal state is final - a reservation is never reused.
 */
public enum ReservationStatus {
    HELD,
    CAPTURED,
    RELEASED
}
