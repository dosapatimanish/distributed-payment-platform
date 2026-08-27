package com.paymentplatform.merchantpayment.web;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record PaymentRequest(
        @NotBlank @Pattern(regexp = "\\d{16}", message = "must be a 16-digit transaction id") String transactionId,
        @NotBlank String merchantId,
        @NotNull @DecimalMin(value = "0.0001") BigDecimal amount,
        @NotBlank @Size(min = 3, max = 3) String currency
) {
}
