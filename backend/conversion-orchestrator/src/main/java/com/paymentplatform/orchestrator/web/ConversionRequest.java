package com.paymentplatform.orchestrator.web;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record ConversionRequest(
        @NotBlank String userId,
        @NotBlank String sourceWalletId,
        @NotBlank String destWalletId,
        @NotBlank @Size(min = 3, max = 3) String sourceCurrency,
        @NotBlank @Size(min = 3, max = 3) String destCurrency,
        @NotNull @DecimalMin(value = "0.0001") BigDecimal sourceAmount
) {
}
