package com.paymentplatform.orchestrator.client;

import com.paymentplatform.orchestrator.client.dto.LedgerLineRequest;
import com.paymentplatform.orchestrator.client.dto.PostEntriesRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Synchronous HTTP client for ledger-service's {@code postEntries} endpoint - see
 * {@link WalletServiceClient}'s javadoc for the shared reasoning. The response body (the saved
 * entries, echoed back) isn't used for anything downstream, so this discards it the same way
 * {@link MerchantPaymentServiceClient#refund} does.
 */
@Component
public class LedgerServiceClient {

    private final RestClient restClient;

    public LedgerServiceClient(RestClient.Builder restClientBuilder,
                                @Value("${orchestrator.ledger-service.base-url}") String baseUrl) {
        this.restClient = restClientBuilder.baseUrl(baseUrl).build();
    }

    public void postEntries(String transactionId, List<LedgerLineRequest> entries, String idempotencyKey) {
        restClient.post()
                .uri("/api/v1/ledger/entries")
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new PostEntriesRequest(transactionId, entries))
                .retrieve()
                .toBodilessEntity();
    }
}
