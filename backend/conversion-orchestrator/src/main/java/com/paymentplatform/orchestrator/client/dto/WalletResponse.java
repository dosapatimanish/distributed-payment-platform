package com.paymentplatform.orchestrator.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

/**
 * Local copy of wallet-service's response shape, trimmed to the fields this service actually
 * uses - {@code @JsonIgnoreProperties(ignoreUnknown = true)} so wallet-service adding fields
 * later doesn't break deserialization here.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WalletResponse(String walletId, BigDecimal balance, String status) {
}
