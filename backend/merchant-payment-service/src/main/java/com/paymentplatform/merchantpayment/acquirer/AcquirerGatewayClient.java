package com.paymentplatform.merchantpayment.acquirer;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Stand-in for a real external payment acquirer (design doc's {@code AcquirerGatewayClient} -
 * §6.3.4 calls for a "Resilience4j circuit-breaker-wrapped client to the external acquirer").
 * No real HTTP call happens here, and no circuit breaker is wired in either - there is nothing
 * external yet to protect against, so wrapping a mock in Resilience4j would just be decoration.
 * Deliberately deferred until a real acquirer integration exists to actually need it.
 *
 * Deterministic, not random: every charge for {@code decline-merchant-id} (config-driven) is
 * declined; every other merchant is approved. This is what lets {@code payment.failed} and a
 * declined-payment path be exercised on demand in tests/manual verification, rather than only
 * ever seeing the happy path - same spirit as fx-rate-service's simulated rate feed.
 */
@Component
public class AcquirerGatewayClient {

    private final String declineMerchantId;

    public AcquirerGatewayClient(@Value("${merchantpayment.acquirer.decline-merchant-id}") String declineMerchantId) {
        this.declineMerchantId = declineMerchantId;
    }

    public AcquirerChargeResult charge(String merchantId, BigDecimal amount, String currency) {
        if (declineMerchantId.equals(merchantId)) {
            return AcquirerChargeResult.declined("Acquirer declined the charge for merchant " + merchantId);
        }
        return AcquirerChargeResult.approved("acq-" + UUID.randomUUID());
    }

    /** Always succeeds in this mock - see class javadoc; a configurable refund-failure hook is a deferred addition, not yet needed. */
    public String refund(String acquirerRef) {
        return "acq-refund-" + UUID.randomUUID();
    }
}
