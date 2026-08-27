package com.paymentplatform.wallet.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A single-currency balance owned by a customer (identified by CIF). One CIF can have at most
 * one wallet per currency (see the unique constraint below).
 *
 * The primary key is a 12-character bank-style account number,
 * {@code [currency short_code 2][CIF prefix 5][sequence 5]} - see
 * backend-documents/identifier-scheme.md and {@code AccountNumberGenerator}.
 *
 * Concurrency control (design doc §6.2.1): every wallet carries a {@code version} column that
 * JPA uses for optimistic locking - the default path for ordinary wallets. A small number of
 * "hot" wallets are flagged {@link #highContention}; those go through pessimistic locking
 * (SELECT ... FOR UPDATE) instead. See {@code WalletService} for where that decision is made.
 */
@Entity
@Table(
        name = "wallet",
        uniqueConstraints = @UniqueConstraint(name = "uk_wallet_cif_currency", columnNames = {"cif", "currency"}),
        indexes = @Index(name = "idx_wallet_cif", columnList = "cif")
)
public class Wallet {

    /** 12-char account number - {@code [currency short_code][CIF prefix][sequence]}. */
    @Id
    @Column(name = "account_no", length = 12, nullable = false, updatable = false)
    private String accountNo;

    /** 10-digit customer number, client-supplied. */
    @Column(name = "cif", length = 10, nullable = false)
    private String cif;

    /** ISO 4217 currency code, e.g. "USD". */
    @Column(name = "currency", length = 3, nullable = false)
    private String currency;

    @Column(name = "balance", precision = 18, scale = 4, nullable = false)
    private BigDecimal balance;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private WalletStatus status;

    /** Config-driven flag (design doc §6.2.1): true routes this wallet's mutations through pessimistic locking. */
    @Column(name = "high_contention", nullable = false)
    private boolean highContention;

    /** JPA optimistic-lock column. Incremented automatically by Hibernate on every update. */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** JPA requires a no-arg constructor; not for application use. */
    protected Wallet() {
    }

    public Wallet(String accountNo, String cif, String currency, BigDecimal balance,
                  WalletStatus status, boolean highContention) {
        this.accountNo = accountNo;
        this.cif = cif;
        this.currency = currency;
        this.balance = balance;
        this.status = status;
        this.highContention = highContention;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public String getAccountNo() {
        return accountNo;
    }

    public String getCif() {
        return cif;
    }

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public void setBalance(BigDecimal balance) {
        this.balance = balance;
    }

    public WalletStatus getStatus() {
        return status;
    }

    public void setStatus(WalletStatus status) {
        this.status = status;
    }

    public boolean isHighContention() {
        return highContention;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
