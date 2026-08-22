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
 * A single-currency balance owned by a user. One user can have at most one wallet per currency
 * (see the unique constraint below).
 *
 * Concurrency control (design doc section 6.2.1): every wallet carries a {@code version} column
 * that JPA uses for optimistic locking - the default path for ordinary wallets. A small number
 * of "hot" wallets (e.g. a platform fee pool hit by many transactions per second) are flagged
 * {@link #highContention}; those go through pessimistic locking (SELECT ... FOR UPDATE) instead.
 * See {@code WalletService} for exactly where that decision is made.
 */
@Entity
@Table(
        name = "wallet",
        uniqueConstraints = @UniqueConstraint(name = "uk_wallet_user_currency", columnNames = {"user_id", "currency"}),
        indexes = @Index(name = "idx_wallet_user_id", columnList = "user_id")
)
public class Wallet {

    /** App-generated UUID string, not a DB-native UUID type - keeps the schema portable to Oracle later. */
    @Id
    @Column(name = "wallet_id", length = 36, nullable = false, updatable = false)
    private String walletId;

    @Column(name = "user_id", nullable = false)
    private String userId;

    /** ISO 4217 currency code, e.g. "USD". */
    @Column(name = "currency", length = 3, nullable = false)
    private String currency;

    @Column(name = "balance", precision = 18, scale = 4, nullable = false)
    private BigDecimal balance;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private WalletStatus status;

    /** Config-driven flag (design doc 6.2.1): true routes this wallet's mutations through pessimistic locking. */
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

    public Wallet(String walletId, String userId, String currency, BigDecimal balance,
                  WalletStatus status, boolean highContention) {
        this.walletId = walletId;
        this.userId = userId;
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

    public String getWalletId() {
        return walletId;
    }

    public String getUserId() {
        return userId;
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
