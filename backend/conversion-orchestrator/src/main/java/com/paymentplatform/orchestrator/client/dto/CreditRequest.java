package com.paymentplatform.orchestrator.client.dto;

import java.math.BigDecimal;

/** Local copy of wallet-service's request shape - no shared library between services, see implementation notes. */
public record CreditRequest(BigDecimal amount, String transactionId) {
}
