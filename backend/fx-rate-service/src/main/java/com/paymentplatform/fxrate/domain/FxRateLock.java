package com.paymentplatform.fxrate.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A short-lived promise that a specific rate will be honoured for a specific conversion
 * transaction (design doc 6.1.2, 6.2.2). Created by {@code FxRateService.lockRate} off the
 * current cached rate; the orchestrator later either consumes it (rate actually used - see
 * WalletService.debit at the source wallet) or releases it (saga compensated before the rate
 * was used).
 *
 * {@code transactionId} is UNIQUE - one active lock per conversion, so a retried lock request
 * for the same transaction can't create a second, possibly different, rate for it.
 */
@Entity
@Table(
        name = "fx_rate_lock",
        uniqueConstraints = @UniqueConstraint(name = "uk_fx_rate_lock_transaction_id", columnNames = "transaction_id")
)
public class FxRateLock {

    @Id
    @Column(name = "lock_id", length = 20, nullable = false, updatable = false)
    private String lockId;

    @Column(name = "transaction_id", length = 16, nullable = false, updatable = false)
    private String transactionId;

    @Column(name = "base_currency", length = 3, nullable = false, updatable = false)
    private String baseCurrency;

    @Column(name = "quote_currency", length = 3, nullable = false, updatable = false)
    private String quoteCurrency;

    @Column(name = "locked_rate", precision = 18, scale = 8, nullable = false, updatable = false)
    private BigDecimal lockedRate;

    @Column(name = "amount", precision = 18, scale = 4, nullable = false, updatable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private RateLockStatus status;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected FxRateLock() {
    }

    public FxRateLock(String lockId, String transactionId, String baseCurrency, String quoteCurrency,
                       BigDecimal lockedRate, BigDecimal amount, RateLockStatus status, Instant expiresAt) {
        this.lockId = lockId;
        this.transactionId = transactionId;
        this.baseCurrency = baseCurrency;
        this.quoteCurrency = quoteCurrency;
        this.lockedRate = lockedRate;
        this.amount = amount;
        this.status = status;
        this.expiresAt = expiresAt;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    public String getLockId() {
        return lockId;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public String getBaseCurrency() {
        return baseCurrency;
    }

    public String getQuoteCurrency() {
        return quoteCurrency;
    }

    public BigDecimal getLockedRate() {
        return lockedRate;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public RateLockStatus getStatus() {
        return status;
    }

    public void setStatus(RateLockStatus status) {
        this.status = status;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
