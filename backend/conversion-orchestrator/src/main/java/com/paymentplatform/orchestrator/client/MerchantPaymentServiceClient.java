package com.paymentplatform.orchestrator.client;

import com.paymentplatform.orchestrator.client.dto.PaymentRequest;
import com.paymentplatform.orchestrator.client.dto.PaymentResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;

/**
 * Synchronous HTTP client for merchant-payment-service's charge/refund endpoints - see
 * {@link WalletServiceClient}'s javadoc for the shared reasoning. Unlike the other two clients,
 * a declined charge is not an HTTP error here - {@link #pay} always returns a body, and the
 * caller must check {@link PaymentResponse#isCompleted()} to learn the real outcome.
 */
@Component
public class MerchantPaymentServiceClient {

    private final RestClient restClient;

    public MerchantPaymentServiceClient(RestClient.Builder restClientBuilder,
                                         @Value("${orchestrator.merchant-payment-service.base-url}") String baseUrl) {
        this.restClient = restClientBuilder.baseUrl(baseUrl).build();
    }

    public PaymentResponse pay(String transactionId, String merchantId, BigDecimal amount, String currency,
                                String idempotencyKey) {
        return restClient.post()
                .uri("/api/v1/merchant-payments")
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new PaymentRequest(transactionId, merchantId, amount, currency))
                .retrieve()
                .body(PaymentResponse.class);
    }

    /**
     * No Idempotency-Key needed - merchant-payment-service's refund is already idempotent by
     * design (see its own docs). Not currently called anywhere in this service - a successful
     * payment is always this saga's last step, so nothing downstream can fail and need it
     * reversed yet. Kept ready for when that changes (e.g. a Ledger-posting step after payment).
     */
    public void refund(String paymentId) {
        restClient.post()
                .uri("/api/v1/merchant-payments/{paymentId}/refund", paymentId)
                .retrieve()
                .toBodilessEntity();
    }
}
