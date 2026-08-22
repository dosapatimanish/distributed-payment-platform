package com.paymentplatform.wallet.event;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Published to {@code wallet.debit.failed} (design doc 6.5) when a debit attempt fails for any
 * reason (wallet not found, not active, insufficient funds, or a concurrency conflict) - the
 * orchestrator's cue to run its compensation path for this step of the saga.
 */
public record WalletDebitFailedEvent(
        String walletId,
        String transactionId,
        BigDecimal amount,
        String reason,
        Instant occurredAt
) {
}
