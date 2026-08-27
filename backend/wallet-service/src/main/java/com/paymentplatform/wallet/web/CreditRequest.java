package com.paymentplatform.wallet.web;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;

public record CreditRequest(
        @NotNull @DecimalMin(value = "0.0001") BigDecimal amount,
        @NotBlank @Pattern(regexp = "\\d{16}", message = "must be a 16-digit transaction id") String transactionId
) {
}
