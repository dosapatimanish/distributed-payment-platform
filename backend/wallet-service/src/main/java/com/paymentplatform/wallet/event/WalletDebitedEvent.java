package com.paymentplatform.wallet.event;

import java.math.BigDecimal;
import java.time.Instant;

/** Published to {@code wallet.debited} (design doc 6.5) after a debit actually commits. */
public record WalletDebitedEvent(
        String walletId,
        String transactionId,
        BigDecimal amount,
        BigDecimal balanceAfter,
        Instant occurredAt
) {
}
