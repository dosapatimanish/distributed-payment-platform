package com.paymentplatform.orchestrator.web;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record ConversionRequest(
        @NotBlank @Pattern(regexp = "\\d{10}", message = "must be a 10-digit CIF") String cif,
        @NotBlank @Pattern(regexp = "\\d{12}", message = "must be a 12-digit account number") String sourceAccountNo,
        @NotBlank @Pattern(regexp = "\\d{12}", message = "must be a 12-digit account number") String destAccountNo,
        @NotBlank @Size(min = 3, max = 3) String sourceCurrency,
        @NotBlank @Size(min = 3, max = 3) String destCurrency,
        @NotNull @DecimalMin(value = "0.0001") BigDecimal sourceAmount,
        /**
         * Optional. Absent (or blank) → plain wallet-to-wallet conversion. Present → after the
         * conversion, the saga also charges this merchant for the converted amount.
         */
        String merchantId
) {
    public boolean hasMerchantId() {
        return merchantId != null && !merchantId.isBlank();
    }
}
