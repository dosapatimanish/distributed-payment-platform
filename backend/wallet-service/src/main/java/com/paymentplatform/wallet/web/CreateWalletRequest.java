package com.paymentplatform.wallet.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateWalletRequest(
        @NotBlank @Pattern(regexp = "\\d{10}", message = "must be exactly 10 digits") String cif,
        @NotBlank @Size(min = 3, max = 3) String currency,
        boolean highContention
) {
}
