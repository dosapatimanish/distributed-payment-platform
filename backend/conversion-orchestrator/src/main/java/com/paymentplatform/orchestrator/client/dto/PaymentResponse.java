package com.paymentplatform.orchestrator.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Local copy of merchant-payment-service's response shape, trimmed to the fields this service
 * actually uses. {@code status} is a plain String ("COMPLETED"/"FAILED"/...), not an enum here -
 * merchant-payment-service's {@code pay} endpoint always returns {@code 201} regardless of
 * whether the charge was approved or declined (a decline is a business outcome, not a request
 * error - see merchant-payment-service-api/01-pay.md), so the caller must inspect this field,
 * not catch an exception, to learn the outcome.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PaymentResponse(String paymentId, String status) {

    public boolean isCompleted() {
        return "COMPLETED".equals(status);
    }
}
