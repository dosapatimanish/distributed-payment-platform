package com.paymentplatform.merchantpayment.web;

import com.paymentplatform.merchantpayment.domain.MerchantPayment;
import com.paymentplatform.merchantpayment.idempotency.IdempotencyGuard;
import com.paymentplatform.merchantpayment.service.MerchantPaymentService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * {@code pay} requires an {@code Idempotency-Key} header - see {@link IdempotencyGuard}'s
 * javadoc for why this goes beyond the design doc's literal REST contract table. {@code refund}
 * is already idempotent at the business layer (no-op on an already-REFUNDED payment) and
 * {@code getPayment} is read-only, so neither needs one.
 */
@RestController
@RequestMapping("/api/v1/merchant-payments")
public class MerchantPaymentController {

    private final MerchantPaymentService paymentService;
    private final IdempotencyGuard idempotencyGuard;

    public MerchantPaymentController(MerchantPaymentService paymentService, IdempotencyGuard idempotencyGuard) {
        this.paymentService = paymentService;
        this.idempotencyGuard = idempotencyGuard;
    }

    /**
     * Always {@code 201} regardless of whether the acquirer approved or declined the charge -
     * {@code status} in the body (COMPLETED vs FAILED) is the real outcome, same reasoning as
     * conversion-orchestrator's {@code startConversion}: a decline is a business outcome of
     * attempting a charge, not a request-level error.
     */
    @PostMapping
    public ResponseEntity<PaymentResponse> pay(@RequestHeader("Idempotency-Key") String idempotencyKey,
                                                @Valid @RequestBody PaymentRequest request) {
        PaymentResponse response = idempotencyGuard.runIdempotent(idempotencyKey, PaymentResponse.class, () -> {
            MerchantPayment payment = paymentService.pay(
                    request.transactionId(), request.merchantId(), request.amount(), request.currency());
            return PaymentResponse.from(payment);
        });
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .location(URI.create("/api/v1/merchant-payments/" + response.paymentId()))
                .body(response);
    }

    @PostMapping("/{paymentId}/refund")
    public PaymentResponse refund(@PathVariable String paymentId) {
        return PaymentResponse.from(paymentService.refund(paymentId));
    }

    @GetMapping("/{paymentId}")
    public PaymentResponse getPayment(@PathVariable String paymentId) {
        return PaymentResponse.from(paymentService.getPayment(paymentId));
    }
}
