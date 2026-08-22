package com.paymentplatform.fxrate.web;

import com.paymentplatform.fxrate.domain.FxRateLock;
import com.paymentplatform.fxrate.domain.RateLockStatus;

import java.math.BigDecimal;
import java.time.Instant;

public record RateLockResponse(
        String lockId,
        String transactionId,
        String baseCurrency,
        String quoteCurrency,
        BigDecimal lockedRate,
        BigDecimal amount,
        RateLockStatus status,
        Instant createdAt,
        Instant expiresAt
) {
    public static RateLockResponse from(FxRateLock lock) {
        return new RateLockResponse(
                lock.getLockId(),
                lock.getTransactionId(),
                lock.getBaseCurrency(),
                lock.getQuoteCurrency(),
                lock.getLockedRate(),
                lock.getAmount(),
                lock.getStatus(),
                lock.getCreatedAt(),
                lock.getExpiresAt()
        );
    }
}
