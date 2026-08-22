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
 * One conversion saga's persisted state (design doc 6.1.3 {@code conversion_transaction}) - the
 * single source of truth {@link com.paymentplatform.orchestrator.service.ConversionService}
 * reads and writes at every step, so a crash mid-saga leaves an accurate record of exactly how
 * far it got (though this pass does not yet implement crash-recovery/resume - see
 * implementation notes).
 *
 * Implements {@link Persistable} because {@code transactionId} is an application-assigned UUID,
 * not {@code @GeneratedValue}: without this, Spring Data JPA's default "is this new?" check
 * (no {@code @Version} field here, unlike {@code Wallet}) falls back to "is the id null?", which
 * is always false the instant the constructor below runs - so every {@code save()} call,
 * including the very first one, would look like an update to an existing row and go through
 * {@code merge()} instead of {@code persist()}. {@code merge()} returns a *different* managed
 * instance with the DB-computed fields (like {@code createdAt}, set by {@code @PrePersist})
 * populated on it - the original object passed to {@code save()} never gets those fields filled
 * in. This bit in practice: the very first response from {@code POST /conversions} showed
 * {@code createdAt}/{@code updatedAt} as {@code null}, even though a subsequent {@code GET}
 * on the same transaction showed them populated correctly (a fresh DB read, unaffected by which
 * in-memory object had stale fields). {@code isNew} here makes the new-vs-existing decision
 * explicit and correct regardless of the pre-assigned id.
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
    @Column(name = "transaction_id", length = 36, nullable = false, updatable = false)
    private String transactionId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private String userId;

    @Column(name = "source_wallet_id", nullable = false, updatable = false)
    private String sourceWalletId;

    @Column(name = "dest_wallet_id", nullable = false, updatable = false)
    private String destWalletId;

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

    /** Null until {@link SagaState#RATE_LOCKED}; fx-rate-service's lock id, needed to consume/release it later. */
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

    public ConversionTransaction(String transactionId, String userId, String sourceWalletId, String destWalletId,
                                  String sourceCurrency, String destCurrency, BigDecimal sourceAmount,
                                  String idempotencyKey) {
        this.transactionId = transactionId;
        this.userId = userId;
        this.sourceWalletId = sourceWalletId;
        this.destWalletId = destWalletId;
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

    /** Entities loaded from the DB (as opposed to newly constructed) are never "new". */
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

    public String getUserId() {
        return userId;
    }

    public String getSourceWalletId() {
        return sourceWalletId;
    }

    public String getDestWalletId() {
        return destWalletId;
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
