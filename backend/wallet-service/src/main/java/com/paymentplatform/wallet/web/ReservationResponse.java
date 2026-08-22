package com.paymentplatform.wallet.web;

import com.paymentplatform.wallet.domain.ReservationStatus;
import com.paymentplatform.wallet.domain.WalletReservation;

import java.math.BigDecimal;
import java.time.Instant;

public record ReservationResponse(
        String reservationId,
        String walletId,
        String transactionId,
        BigDecimal amount,
        ReservationStatus status,
        Instant createdAt,
        Instant expiresAt
) {
    public static ReservationResponse from(WalletReservation reservation) {
        return new ReservationResponse(
                reservation.getReservationId(),
                reservation.getWalletId(),
                reservation.getTransactionId(),
                reservation.getAmount(),
                reservation.getStatus(),
                reservation.getCreatedAt(),
                reservation.getExpiresAt()
        );
    }
}
