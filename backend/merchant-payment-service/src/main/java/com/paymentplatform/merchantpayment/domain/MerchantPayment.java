package com.paymentplatform.merchantpayment.domain;

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
 * One attempt to charge a merchant (design doc 6.1.4). {@code transactionId} is UNIQUE - the
 * saga/business transaction this payment belongs to; the DB unique constraint is what actually
 * stops a genuine duplicate charge attempt, same role as {@code fx_rate_lock.transaction_id}.
 *
 * Implements {@link Persistable} for the same reason as conversion-orchestrator's
 * {@code ConversionTransaction} - {@code paymentId} is an application-assigned UUID, not
 * {@code @GeneratedValue}, and there is no {@code @Version} field here, so without this Spring
 * Data JPA's default new-vs-existing check would route even the very first {@code save()}
 * through {@code merge()} instead of {@code persist()}, silently dropping the
 * {@code @PrePersist}-set {@code createdAt}/{@code updatedAt} from the object the caller already
 * holds. See conversion-orchestrator-implementation.md's "A real bug this caught" section for
 * the full story - applied here from the start instead of rediscovering it.
 */
@Entity
@Table(
        name = "merchant_payment",
        uniqueConstraints = @UniqueConstraint(name = "uk_merchant_payment_transaction_id", columnNames = "transaction_id")
)
public class MerchantPayment implements Persistable<String> {

    @Transient
    private boolean isNew = true;

    @Id
    @Column(name = "payment_id", length = 20, nullable = false, updatable = false)
    private String paymentId;

    @Column(name = "transaction_id", length = 16, nullable = false, updatable = false)
    private String transactionId;

    @Column(name = "merchant_id", nullable = false, updatable = false)
    private String merchantId;

    @Column(name = "amount", precision = 18, scale = 4, nullable = false, updatable = false)
    private BigDecimal amount;

    @Column(name = "currency", length = 3, nullable = false, updatable = false)
    private String currency;

    /** External acquirer reference - null if the charge was declined before one was issued. */
    @Column(name = "acquirer_ref", length = 64)
    private String acquirerRef;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private PaymentStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected MerchantPayment() {
    }

    public MerchantPayment(String paymentId, String transactionId, String merchantId, BigDecimal amount,
                            String currency, String acquirerRef, PaymentStatus status) {
        this.paymentId = paymentId;
        this.transactionId = transactionId;
        this.merchantId = merchantId;
        this.amount = amount;
        this.currency = currency;
        this.acquirerRef = acquirerRef;
        this.status = status;
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
        return paymentId;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    public String getPaymentId() {
        return paymentId;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public String getMerchantId() {
        return merchantId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public String getAcquirerRef() {
        return acquirerRef;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public void setStatus(PaymentStatus status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
