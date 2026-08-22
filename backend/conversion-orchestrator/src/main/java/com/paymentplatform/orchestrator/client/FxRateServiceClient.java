package com.paymentplatform.orchestrator.client;

import com.paymentplatform.orchestrator.client.dto.RateLockRequest;
import com.paymentplatform.orchestrator.client.dto.RateLockResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;

/** Synchronous HTTP client for fx-rate-service's rate-lock endpoints - see {@link WalletServiceClient}'s javadoc for the shared reasoning. */
@Component
public class FxRateServiceClient {

    private final RestClient restClient;

    public FxRateServiceClient(RestClient.Builder restClientBuilder,
                                @Value("${orchestrator.fx-rate-service.base-url}") String baseUrl) {
        this.restClient = restClientBuilder.baseUrl(baseUrl).build();
    }

    public RateLockResponse lockRate(String baseCurrency, String quoteCurrency, BigDecimal amount,
                                      String transactionId, String idempotencyKey) {
        return restClient.post()
                .uri("/api/v1/fx/rate-lock")
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new RateLockRequest(baseCurrency, quoteCurrency, amount, transactionId))
                .retrieve()
                .body(RateLockResponse.class);
    }

    public void consumeLock(String lockId, String idempotencyKey) {
        restClient.post()
                .uri("/api/v1/fx/rate-lock/{lockId}/consume", lockId)
                .header("Idempotency-Key", idempotencyKey)
                .retrieve()
                .toBodilessEntity();
    }

    /** No Idempotency-Key needed - fx-rate-service's release is already idempotent by design (see its own docs). */
    public void releaseLock(String lockId) {
        restClient.delete()
                .uri("/api/v1/fx/rate-lock/{lockId}", lockId)
                .retrieve()
                .toBodilessEntity();
    }
}
