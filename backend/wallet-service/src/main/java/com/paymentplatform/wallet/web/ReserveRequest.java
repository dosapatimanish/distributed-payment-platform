package com.paymentplatform.wallet.web;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record ReserveRequest(
        @NotNull @DecimalMin(value = "0.0001") BigDecimal amount,
        @NotBlank String transactionId
) {
}
