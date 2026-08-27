package com.paymentplatform.orchestrator.client;

import com.paymentplatform.orchestrator.client.dto.CurrencyDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Reads wallet-service's currency reference data. {@code CurrencyCache} calls this to resolve
 * the 2-digit short_code a transaction id starts with.
 */
@Component
public class CurrencyServiceClient {

    private final RestClient restClient;

    public CurrencyServiceClient(RestClient.Builder restClientBuilder,
                                  @Value("${orchestrator.wallet-service.base-url}") String baseUrl) {
        this.restClient = restClientBuilder.baseUrl(baseUrl).build();
    }

    public List<CurrencyDto> getAll() {
        return restClient.get()
                .uri("/api/v1/currencies")
                .retrieve()
                .body(new ParameterizedTypeReference<List<CurrencyDto>>() {});
    }
}
