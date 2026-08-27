package com.paymentplatform.wallet.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A hold placed against a wallet's balance - money the wallet has promised but not yet actually
 * moved. Deliberately holds {@code accountNo} as a plain string column rather than a JPA
 * {@code @ManyToOne} association: WalletService always loads the target Wallet itself,
 * explicitly, inside whichever locking strategy (optimistic/pessimistic) it decided on.
 */
@Entity
@Table(name = "wallet_reservation")
public class WalletReservation {

    @Id
    @Column(name = "reservation_id", length = 20, nullable = false, updatable = false)
    private String reservationId;

    @Column(name = "account_no", length = 12, nullable = false)
    private String accountNo;

    /** The SAGA/business transaction this hold belongs to (16-digit transaction id). */
    @Column(name = "transaction_id", length = 16, nullable = false)
    private String transactionId;

    @Column(name = "amount", precision = 18, scale = 4, nullable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private ReservationStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected WalletReservation() {
    }

    public WalletReservation(String reservationId, String accountNo, String transactionId,
                              BigDecimal amount, ReservationStatus status, Instant expiresAt) {
        this.reservationId = reservationId;
        this.accountNo = accountNo;
        this.transactionId = transactionId;
        this.amount = amount;
        this.status = status;
        this.expiresAt = expiresAt;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    public String getReservationId() {
        return reservationId;
    }

    public String getAccountNo() {
        return accountNo;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public ReservationStatus getStatus() {
        return status;
    }

    public void setStatus(ReservationStatus status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
