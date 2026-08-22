package com.paymentplatform.wallet.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateWalletRequest(
        @NotBlank String userId,
        @NotBlank @Size(min = 3, max = 3) String currency,
        boolean highContention
) {
}
