package com.paymentplatform.merchantpayment.web;

import com.paymentplatform.merchantpayment.domain.MerchantPayment;
import com.paymentplatform.merchantpayment.domain.PaymentStatus;

import java.math.BigDecimal;
import java.time.Instant;

public record PaymentResponse(
        String paymentId,
        String transactionId,
        String merchantId,
        BigDecimal amount,
        String currency,
        String acquirerRef,
        PaymentStatus status,
        Instant createdAt,
        Instant updatedAt
) {
    public static PaymentResponse from(MerchantPayment payment) {
        return new PaymentResponse(
                payment.getPaymentId(),
                payment.getTransactionId(),
                payment.getMerchantId(),
                payment.getAmount(),
                payment.getCurrency(),
                payment.getAcquirerRef(),
                payment.getStatus(),
                payment.getCreatedAt(),
                payment.getUpdatedAt()
        );
    }
}
