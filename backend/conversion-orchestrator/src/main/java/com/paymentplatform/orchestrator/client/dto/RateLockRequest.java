package com.paymentplatform.orchestrator.client.dto;

import java.math.BigDecimal;

/** Local copy of fx-rate-service's request shape - no shared library between services, see implementation notes. */
public record RateLockRequest(String baseCurrency, String quoteCurrency, BigDecimal amount, String transactionId) {
}
