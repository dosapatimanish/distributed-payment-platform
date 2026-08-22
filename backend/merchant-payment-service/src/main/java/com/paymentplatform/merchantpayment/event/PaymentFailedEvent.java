package com.paymentplatform.merchantpayment.event;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Published to {@code payment.failed} (design doc 6.5) after an acquirer charge is declined -
 * the orchestrator's cue (once it consumes this - see conversion-orchestrator's "what's next")
 * to run its compensation path.
 */
public record PaymentFailedEvent(
        String transactionId,
        String paymentId,
        BigDecimal amount,
        String currency,
        String reason,
        Instant occurredAt
) {
}
