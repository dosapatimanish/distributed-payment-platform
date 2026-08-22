package com.paymentplatform.orchestrator.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

/** Local copy of fx-rate-service's response shape, trimmed to the fields this service actually uses. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RateLockResponse(String lockId, BigDecimal lockedRate, String status) {
}
