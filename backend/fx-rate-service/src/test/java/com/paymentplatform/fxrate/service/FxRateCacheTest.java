package com.paymentplatform.fxrate.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FxRateCacheTest {

    @Test
    void get_beforeAnyRefresh_returnsEmpty() {
        FxRateCache cache = new FxRateCache();

        assertThat(cache.get("USD", "INR")).isEmpty();
    }

    @Test
    void refresh_thenGet_returnsTheSnapshotJustSet() {
        FxRateCache cache = new FxRateCache();
        FxRateCache.RateSnapshot snapshot = new FxRateCache.RateSnapshot(
                new BigDecimal("83.0000"), "SIMULATED_FEED", Instant.now());

        cache.refresh(Map.of(FxRateCache.key("USD", "INR"), snapshot));

        assertThat(cache.get("USD", "INR")).contains(snapshot);
    }

    @Test
    void refresh_replacesWholeSnapshot_pairsMissingFromNewMapDisappear() {
        FxRateCache cache = new FxRateCache();
        cache.refresh(Map.of(FxRateCache.key("USD", "INR"),
                new FxRateCache.RateSnapshot(new BigDecimal("83.0000"), "SIMULATED_FEED", Instant.now())));

        // Next tick only carries USD/EUR - USD/INR was dropped from the tracked pairs.
        cache.refresh(Map.of(FxRateCache.key("USD", "EUR"),
                new FxRateCache.RateSnapshot(new BigDecimal("0.92"), "SIMULATED_FEED", Instant.now())));

        assertThat(cache.get("USD", "INR")).isEmpty();
        assertThat(cache.get("USD", "EUR")).isPresent();
    }
}
