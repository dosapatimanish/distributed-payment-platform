package com.paymentplatform.orchestrator.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;
import org.springframework.data.domain.Persistable;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One conversion saga's persisted state (design doc §6.1.3). Single source of truth
 * {@link com.paymentplatform.orchestrator.service.ConversionService} reads and writes at every
 * step. {@code transactionId} is a 16-char bank-style id
 * ({@code [source-currency short_code 2][business date YYYYMMDD 8][daily sequence 6]}) minted by
 * {@code TransactionIdGenerator} - see backend-documents/identifier-scheme.md.
 *
 * Implements {@link Persistable} because {@code transactionId} is application-assigned, not
 * {@code @GeneratedValue}, and there is no {@code @Version} field: {@code isNew} makes the
 * new-vs-existing decision explicit so the first {@code save()} goes through {@code persist()}
 * (which fills {@code @PrePersist} fields on the passed instance), not {@code merge()}.
 */
@Entity
@Table(
        name = "conversion_transaction",
        uniqueConstraints = @UniqueConstraint(name = "uk_conversion_transaction_idempotency_key", columnNames = "idempotency_key")
)
public class ConversionTransaction implements Persistable<String> {

    @Transient
    private boolean isNew = true;

    @Id
    @Column(name = "transaction_id", length = 16, nullable = false, updatable = false)
    private String transactionId;

    @Column(name = "cif", length = 10, nullable = false, updatable = false)
    private String cif;

    @Column(name = "source_account_no", length = 12, nullable = false, updatable = false)
    private String sourceAccountNo;

    @Column(name = "dest_account_no", length = 12, nullable = false, updatable = false)
    private String destAccountNo;

    @Column(name = "source_currency", length = 3, nullable = false, updatable = false)
    private String sourceCurrency;

    @Column(name = "dest_currency", length = 3, nullable = false, updatable = false)
    private String destCurrency;

    @Column(name = "source_amount", precision = 18, scale = 4, nullable = false, updatable = false)
    private BigDecimal sourceAmount;

    /** Null until the rate lock succeeds; {@code sourceAmount * lockedRate}, scaled. */
    @Column(name = "dest_amount", precision = 18, scale = 4)
    private BigDecimal destAmount;

    /** Null until {@link SagaState#RATE_LOCKED}. */
    @Column(name = "locked_rate", precision = 18, scale = 8)
    private BigDecimal lockedRate;

    /** Null until {@link SagaState#RATE_LOCKED}; fx-rate-service's lock id. */
    @Column(name = "fx_lock_id", length = 36)
    private String fxLockId;

    @Enumerated(EnumType.STRING)
    @Column(name = "saga_state", length = 30, nullable = false)
    private SagaState sagaState;

    @Column(name = "idempotency_key", length = 80, nullable = false, updatable = false)
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ConversionTransaction() {
    }

    public ConversionTransaction(String transactionId, String cif, String sourceAccountNo, String destAccountNo,
                                  String sourceCurrency, String destCurrency, BigDecimal sourceAmount,
                                  String idempotencyKey) {
        this.transactionId = transactionId;
        this.cif = cif;
        this.sourceAccountNo = sourceAccountNo;
        this.destAccountNo = destAccountNo;
        this.sourceCurrency = sourceCurrency;
        this.destCurrency = destCurrency;
        this.sourceAmount = sourceAmount;
        this.idempotencyKey = idempotencyKey;
        this.sagaState = SagaState.STARTED;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
        this.isNew = false;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    @PostLoad
    void onLoad() {
        this.isNew = false;
    }

    @Override
    public String getId() {
        return transactionId;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public String getCif() {
        return cif;
    }

    public String getSourceAccountNo() {
        return sourceAccountNo;
    }

    public String getDestAccountNo() {
        return destAccountNo;
    }

    public String getSourceCurrency() {
        return sourceCurrency;
    }

    public String getDestCurrency() {
        return destCurrency;
    }

    public BigDecimal getSourceAmount() {
        return sourceAmount;
    }

    public BigDecimal getDestAmount() {
        return destAmount;
    }

    public void setDestAmount(BigDecimal destAmount) {
        this.destAmount = destAmount;
    }

    public BigDecimal getLockedRate() {
        return lockedRate;
    }

    public void setLockedRate(BigDecimal lockedRate) {
        this.lockedRate = lockedRate;
    }

    public String getFxLockId() {
        return fxLockId;
    }

    public void setFxLockId(String fxLockId) {
        this.fxLockId = fxLockId;
    }

    public SagaState getSagaState() {
        return sagaState;
    }

    public void setSagaState(SagaState sagaState) {
        this.sagaState = sagaState;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
