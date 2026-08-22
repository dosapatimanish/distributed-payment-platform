package com.paymentplatform.fxrate.service;

import com.paymentplatform.fxrate.domain.FxRate;
import com.paymentplatform.fxrate.repository.FxRateRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RateRefreshSchedulerTest {

    @Mock
    private FxRateRepository repository;

    @Test
    void refreshRates_seedsCacheAndPersistsOneRowPerConfiguredPair() {
        FxRateCache cache = new FxRateCache();
        RateRefreshScheduler scheduler = new RateRefreshScheduler(
                cache, repository, "USD:INR:83.0000,USD:EUR:0.9200");

        scheduler.refreshRates();

        assertThat(cache.get("USD", "INR")).isPresent();
        assertThat(cache.get("USD", "EUR")).isPresent();
        verify(repository, times(2)).save(any(FxRate.class));
    }

    @Test
    void refreshRates_movesRateWithinBoundedStepOfPreviousValue() {
        FxRateCache cache = new FxRateCache();
        RateRefreshScheduler scheduler = new RateRefreshScheduler(cache, repository, "USD:INR:83.0000");

        scheduler.refreshRates();
        BigDecimal afterFirstTick = cache.get("USD", "INR").orElseThrow().rate();
        scheduler.refreshRates();
        BigDecimal afterSecondTick = cache.get("USD", "INR").orElseThrow().rate();

        // MAX_STEP = 0.15% per tick - generous bound (1%) so this isn't flaky, just proves it's
        // a small walk, not an arbitrary jump.
        BigDecimal maxMove = afterFirstTick.multiply(new BigDecimal("0.01"));
        assertThat(afterSecondTick.subtract(afterFirstTick).abs()).isLessThanOrEqualTo(maxMove);
    }

    @Test
    void constructor_malformedPairEntry_throwsImmediately() {
        FxRateCache cache = new FxRateCache();

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new RateRefreshScheduler(cache, repository, "USD-INR-83.0000"));
    }
}
