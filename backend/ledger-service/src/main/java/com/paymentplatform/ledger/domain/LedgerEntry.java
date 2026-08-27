package com.paymentplatform.ledger.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Index;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One immutable double-entry ledger line (design doc §6.1.5). Append-only: a correction is
 * always a new, offsetting entry. Composite PK {@code (transaction_id, entry_no)}: {@code
 * entryNo} is a 2-digit leg number within the posting ({@code 01}, {@code 02}, ...), so a row's
 * position in the sequence is readable from the id (identifier-scheme.md).
 *
 * Implements {@link Persistable} for the same reason as every other application-assigned-id
 * entity here: no {@code @Version} field, so without an explicit {@code isNew} Spring Data JPA's
 * first {@code save()} would go through {@code merge()} and drop the {@code @PrePersist}
 * {@code createdAt} from the caller's instance.
 */
@Entity
@Table(
        name = "ledger_entry",
        indexes = @Index(name = "idx_ledger_entry_account_no", columnList = "account_no")
)
@IdClass(LedgerEntryId.class)
public class LedgerEntry implements Persistable<LedgerEntryId> {

    @Transient
    private boolean isNew = true;

    @Id
    // Wide enough for a compensation reversal's "{16-digit}-reversal" (25 chars).
    @Column(name = "transaction_id", length = 64, nullable = false, updatable = false)
    private String transactionId;

    @Id
    @Column(name = "entry_no", length = 4, nullable = false, updatable = false)
    private String entryNo;

    @Column(name = "account_no", length = 12, nullable = false, updatable = false)
    private String accountNo;

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

    public LedgerEntry(String transactionId, String entryNo, String accountNo, EntryType entryType,
                        BigDecimal amount, String currency, BigDecimal balanceAfter) {
        this.transactionId = transactionId;
        this.entryNo = entryNo;
        this.accountNo = accountNo;
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
    public LedgerEntryId getId() {
        return new LedgerEntryId(transactionId, entryNo);
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public String getEntryNo() {
        return entryNo;
    }

    public String getAccountNo() {
        return accountNo;
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
