package com.paymentplatform.orchestrator.client.dto;

import java.math.BigDecimal;

/** Local copy of merchant-payment-service's request shape - no shared library between services, see implementation notes. */
public record PaymentRequest(String transactionId, String merchantId, BigDecimal amount, String currency) {
}
