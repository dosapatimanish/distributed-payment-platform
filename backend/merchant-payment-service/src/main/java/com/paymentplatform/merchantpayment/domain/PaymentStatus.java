package com.paymentplatform.merchantpayment.domain;

/**
 * Lifecycle status of a merchant payment (design doc 6.1.4).
 * A charge attempt resolves synchronously to COMPLETED or FAILED (this mock acquirer never
 * leaves a payment sitting in PENDING - see AcquirerGatewayClient); COMPLETED can later become
 * REFUNDED. FAILED and REFUNDED are terminal.
 */
public enum PaymentStatus {
    PENDING,
    COMPLETED,
    FAILED,
    REFUNDED
}
