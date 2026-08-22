package com.paymentplatform.fxrate.service;

import com.paymentplatform.fxrate.domain.FxRate;
import com.paymentplatform.fxrate.repository.FxRateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Simulated live-rate feed (design doc 6.3.2). No external provider is wired in yet - deliberate,
 * see fx-rate-service implementation notes - this generates a small random walk around each
 * pair's configured seed rate on every tick instead, so the rest of the service (cache,
 * locking, endpoints) can be built and exercised against something that actually moves.
 *
 * Every tick: computes a new rate per tracked pair, swaps the whole batch into {@link
 * FxRateCache} atomically, and persists the same batch as new {@link FxRate} rows (the
 * append-only audit history).
 */
@Component
public class RateRefreshScheduler {

    private static final Logger log = LoggerFactory.getLogger(RateRefreshScheduler.class);
    private static final String SOURCE = "SIMULATED_FEED";
    private static final int RATE_SCALE = 8;
    /** Max fractional move per tick, e.g. 0.0015 = up to +/-0.15%. */
    private static final double MAX_STEP = 0.0015;

    private record Pair(String base, String quote) {
        String key() {
            return FxRateCache.key(base, quote);
        }
    }

    private final FxRateCache cache;
    private final FxRateRepository repository;
    private final List<Pair> pairs;
    private final Map<String, BigDecimal> lastRates = new HashMap<>();

    public RateRefreshScheduler(FxRateCache cache, FxRateRepository repository,
                                 @Value("${fx.rate.pairs}") String pairsConfig) {
        this.cache = cache;
        this.repository = repository;
        this.pairs = parsePairs(pairsConfig);
    }

    @Scheduled(fixedRateString = "${fx.rate.refresh-interval-ms}")
    public void refreshRates() {
        Instant now = Instant.now();
        Map<String, FxRateCache.RateSnapshot> nextSnapshot = new HashMap<>();
        for (Pair pair : pairs) {
            BigDecimal newRate = nextRate(pair.key());
            lastRates.put(pair.key(), newRate);
            nextSnapshot.put(pair.key(), new FxRateCache.RateSnapshot(newRate, SOURCE, now));
            repository.save(new FxRate(UUID.randomUUID().toString(), pair.base(), pair.quote(), newRate, SOURCE, now));
        }
        cache.refresh(nextSnapshot);
        log.debug("Refreshed {} rate(s) at {}", pairs.size(), now);
    }

    private BigDecimal nextRate(String key) {
        BigDecimal previous = lastRates.get(key);
        double stepFraction = ThreadLocalRandom.current().nextDouble(-MAX_STEP, MAX_STEP);
        BigDecimal moved = previous.add(previous.multiply(BigDecimal.valueOf(stepFraction)));
        // Guard against a pathological drift to zero/negative over a long-running dev instance.
        if (moved.signum() <= 0) {
            moved = previous;
        }
        return moved.setScale(RATE_SCALE, RoundingMode.HALF_UP);
    }

    private List<Pair> parsePairs(String pairsConfig) {
        return java.util.Arrays.stream(pairsConfig.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(entry -> {
                    String[] parts = entry.split(":");
                    if (parts.length != 3) {
                        throw new IllegalArgumentException(
                                "fx.rate.pairs entry must be BASE:QUOTE:seedRate, got: " + entry);
                    }
                    Pair pair = new Pair(parts[0], parts[1]);
                    lastRates.put(pair.key(), new BigDecimal(parts[2]).setScale(RATE_SCALE, RoundingMode.HALF_UP));
                    return pair;
                })
                .toList();
    }
}
