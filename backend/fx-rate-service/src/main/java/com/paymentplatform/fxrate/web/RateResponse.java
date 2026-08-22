package com.paymentplatform.fxrate.web;

import com.paymentplatform.fxrate.service.FxRateCache;

import java.math.BigDecimal;
import java.time.Instant;

public record RateResponse(
        String baseCurrency,
        String quoteCurrency,
        BigDecimal rate,
        String source,
        Instant effectiveAt
) {
    public static RateResponse of(String base, String quote, FxRateCache.RateSnapshot snapshot) {
        return new RateResponse(base, quote, snapshot.rate(), snapshot.source(), snapshot.effectiveAt());
    }
}
