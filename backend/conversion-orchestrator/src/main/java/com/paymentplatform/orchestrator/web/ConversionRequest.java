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
        @NotNull @DecimalMin(value = "0.0001") BigDecimal sourceAmount,
        /**
         * Optional. Absent (or blank) → plain wallet-to-wallet conversion, saga ends at
         * {@code DEST_CREDITED -> COMPLETED}. Present → after the conversion, the saga also
         * charges this merchant for the converted amount via merchant-payment-service, ending
         * at {@code PAYMENT_COMPLETED -> COMPLETED} on approval, or fully compensating (both
         * the credit and the debit reversed) on a decline.
         */
        String merchantId
) {
    public boolean hasMerchantId() {
        return merchantId != null && !merchantId.isBlank();
    }
}
