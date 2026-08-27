package com.paymentplatform.wallet.web;

import com.paymentplatform.wallet.domain.Currency;

public record CurrencyResponse(
        String code,
        String numericCode,
        String shortCode,
        String name,
        int minorUnits,
        boolean active
) {
    public static CurrencyResponse from(Currency c) {
        return new CurrencyResponse(c.getCode(), c.getNumericCode(), c.getShortCode(), c.getName(), c.getMinorUnits(), c.isActive());
    }
}
