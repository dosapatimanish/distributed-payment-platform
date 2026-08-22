package com.paymentplatform.merchantpayment.event;

import java.math.BigDecimal;
import java.time.Instant;

/** Published to {@code payment.completed} (design doc 6.5) after an acquirer charge is approved. */
public record PaymentCompletedEvent(
        String transactionId,
        String paymentId,
        BigDecimal amount,
        String currency,
        String acquirerRef,
        Instant occurredAt
) {
}
