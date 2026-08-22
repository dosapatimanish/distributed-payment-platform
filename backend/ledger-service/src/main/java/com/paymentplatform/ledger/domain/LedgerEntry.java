package com.paymentplatform.ledger.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One immutable double-entry ledger line (design doc §6.1.5) - either the DEBIT or the CREDIT
 * side of a transaction against one wallet. Rows are append-only: no {@code UPDATE}/{@code
 * DELETE} at the application layer, a correction is always a new, offsetting entry (see
 * {@code LedgerService}).
 *
 * Implements {@link Persistable} for the same reason as every other application-assigned-ID
 * entity in this platform (conversion-orchestrator's {@code ConversionTransaction},
 * merchant-payment-service's {@code MerchantPayment}) - {@code entryId} is an application UUID,
 * not {@code @GeneratedValue}, and there is no {@code @Version} field, so without this Spring
 * Data JPA's default new-vs-existing check would route the first {@code save()} through {@code
 * merge()} instead of {@code persist()}, silently dropping the {@code @PrePersist}-set {@code
 * createdAt} from the object the caller already holds.
 */
@Entity
@Table(
        name = "ledger_entry",
        indexes = {
                @Index(name = "idx_ledger_entry_transaction_id", columnList = "transaction_id"),
                @Index(name = "idx_ledger_entry_wallet_id", columnList = "wallet_id")
        }
)
public class LedgerEntry implements Persistable<String> {

    @Transient
    private boolean isNew = true;

    @Id
    @Column(name = "entry_id", length = 36, nullable = false, updatable = false)
    private String entryId;

    // Wider than the design doc's literal VARCHAR2(36) (§6.1.5) - a UUID (36 chars) plus a
    // "-reversal" suffix (design doc §5.3 step 11b's REVERSED entry, see conversion-orchestrator's
    // ConversionService.recordLedgerReversal) needs 45+. A real bug caught in manual testing: the
    // first live compensation scenario hit "value too long for type character varying(36)" on
    // this exact column.
    @Column(name = "transaction_id", length = 64, nullable = false, updatable = false)
    private String transactionId;

    @Column(name = "wallet_id", length = 36, nullable = false, updatable = false)
    private String walletId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", length = 10, nullable = false, updatable = false)
    private EntryType entryType;

    @Column(name = "amount", precision = 18, scale = 4, nullable = false, updatable = false)
    private BigDecimal amount;

    @Column(name = "currency", length = 3, nullable = false, updatable = false)
    private String currency;

    @Column(name = "balance_after", precision = 18, scale = 4, nullable = false, updatable = false)
    private BigDecimal balanceAfter;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected LedgerEntry() {
    }

    public LedgerEntry(String entryId, String transactionId, String walletId, EntryType entryType,
                        BigDecimal amount, String currency, BigDecimal balanceAfter) {
        this.entryId = entryId;
        this.transactionId = transactionId;
        this.walletId = walletId;
        this.entryType = entryType;
        this.amount = amount;
        this.currency = currency;
        this.balanceAfter = balanceAfter;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
        this.isNew = false;
    }

    @PostLoad
    void onLoad() {
        this.isNew = false;
    }

    @Override
    public String getId() {
        return entryId;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    public String getEntryId() {
        return entryId;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public String getWalletId() {
        return walletId;
    }

    public EntryType getEntryType() {
        return entryType;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getBalanceAfter() {
        return balanceAfter;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
