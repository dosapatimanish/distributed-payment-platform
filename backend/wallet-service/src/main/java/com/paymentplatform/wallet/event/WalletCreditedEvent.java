package com.paymentplatform.wallet.event;

import java.math.BigDecimal;
import java.time.Instant;

/** Published to {@code wallet.credited} (design doc 6.5) after a credit actually commits. */
public record WalletCreditedEvent(
        String walletId,
        String transactionId,
        BigDecimal amount,
        BigDecimal balanceAfter,
        Instant occurredAt
) {
}
