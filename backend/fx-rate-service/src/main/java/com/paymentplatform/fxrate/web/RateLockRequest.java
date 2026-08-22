package com.paymentplatform.fxrate.web;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record RateLockRequest(
        @NotBlank @Size(min = 3, max = 3) String baseCurrency,
        @NotBlank @Size(min = 3, max = 3) String quoteCurrency,
        @NotNull @DecimalMin(value = "0.0001") BigDecimal amount,
        @NotBlank String transactionId
) {
}
