package com.paymentplatform.orchestrator.service;

import com.paymentplatform.orchestrator.client.CurrencyServiceClient;
import com.paymentplatform.orchestrator.client.dto.CurrencyDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Caches wallet-service's {@code code -> short_code} currency map. Loaded lazily on first use
 * (not at startup) so this service does not depend on wallet-service being reachable before it
 * can boot - the map is only needed the first time a conversion is started.
 */
@Component
public class CurrencyCache {

    private static final Logger log = LoggerFactory.getLogger(CurrencyCache.class);

    private final CurrencyServiceClient currencyServiceClient;
    private final Map<String, String> shortCodeByCode = new ConcurrentHashMap<>();

    public CurrencyCache(CurrencyServiceClient currencyServiceClient) {
        this.currencyServiceClient = currencyServiceClient;
    }

    /** @return the 2-digit short_code for {@code currencyCode}, refreshing from wallet-service if unknown. */
    public String shortCode(String currencyCode) {
        String cached = shortCodeByCode.get(currencyCode);
        if (cached != null) {
            return cached;
        }
        refresh();
        String code = shortCodeByCode.get(currencyCode);
        if (code == null) {
            throw new IllegalStateException("No currency short_code known for " + currencyCode);
        }
        return code;
    }

    private synchronized void refresh() {
        try {
            Map<String, String> loaded = currencyServiceClient.getAll().stream()
                    .collect(Collectors.toMap(CurrencyDto::code, CurrencyDto::shortCode));
            shortCodeByCode.putAll(loaded);
            log.info("Loaded {} currency short_codes from wallet-service", loaded.size());
        } catch (RuntimeException ex) {
            log.warn("Could not load currencies from wallet-service: {}", ex.getMessage());
        }
    }
}
