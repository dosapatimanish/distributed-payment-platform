package com.paymentplatform.orchestrator.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Local copy of wallet-service's currency shape, trimmed to what the transaction id needs. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CurrencyDto(String code, String shortCode) {
}
