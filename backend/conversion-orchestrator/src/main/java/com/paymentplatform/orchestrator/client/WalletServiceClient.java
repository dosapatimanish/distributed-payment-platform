package com.paymentplatform.orchestrator.client;

import com.paymentplatform.orchestrator.client.dto.CreditRequest;
import com.paymentplatform.orchestrator.client.dto.DebitRequest;
import com.paymentplatform.orchestrator.client.dto.WalletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;

/**
 * Synchronous HTTP client for wallet-service's debit/credit endpoints (design doc 5.3 - this
 * orchestrator issues commands to Wallet/FX synchronously, per the "Synchronous REST calls"
 * scope decision for this pass; see implementation notes for the deferred async-Kafka
 * alternative). Every call carries its own distinct {@code Idempotency-Key}, derived by the
 * caller from the saga's own key plus a step suffix - see {@code ConversionService}.
 */
@Component
public class WalletServiceClient {

    private final RestClient restClient;

    public WalletServiceClient(RestClient.Builder restClientBuilder,
                                @Value("${orchestrator.wallet-service.base-url}") String baseUrl) {
        this.restClient = restClientBuilder.baseUrl(baseUrl).build();
    }

    public WalletResponse debit(String walletId, BigDecimal amount, String transactionId, String idempotencyKey) {
        return restClient.post()
                .uri("/api/v1/wallets/{walletId}/debit", walletId)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new DebitRequest(amount, transactionId))
                .retrieve()
                .body(WalletResponse.class);
    }

    public WalletResponse credit(String walletId, BigDecimal amount, String transactionId, String idempotencyKey) {
        return restClient.post()
                .uri("/api/v1/wallets/{walletId}/credit", walletId)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new CreditRequest(amount, transactionId))
                .retrieve()
                .body(WalletResponse.class);
    }
}
