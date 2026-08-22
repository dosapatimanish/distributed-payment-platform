package com.paymentplatform.orchestrator.web;

import com.paymentplatform.orchestrator.domain.ConversionTransaction;
import com.paymentplatform.orchestrator.domain.SagaState;

import java.math.BigDecimal;
import java.time.Instant;

public record ConversionResponse(
        String transactionId,
        String userId,
        String sourceWalletId,
        String destWalletId,
        String sourceCurrency,
        String destCurrency,
        BigDecimal sourceAmount,
        BigDecimal destAmount,
        BigDecimal lockedRate,
        SagaState sagaState,
        Instant createdAt,
        Instant updatedAt
) {
    public static ConversionResponse from(ConversionTransaction txn) {
        return new ConversionResponse(
                txn.getTransactionId(),
                txn.getUserId(),
                txn.getSourceWalletId(),
                txn.getDestWalletId(),
                txn.getSourceCurrency(),
                txn.getDestCurrency(),
                txn.getSourceAmount(),
                txn.getDestAmount(),
                txn.getLockedRate(),
                txn.getSagaState(),
                txn.getCreatedAt(),
                txn.getUpdatedAt()
        );
    }
}
