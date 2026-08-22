package com.paymentplatform.wallet.web;

import com.paymentplatform.wallet.domain.Wallet;
import com.paymentplatform.wallet.domain.WalletStatus;

import java.math.BigDecimal;
import java.time.Instant;

public record WalletResponse(
        String walletId,
        String userId,
        String currency,
        BigDecimal balance,
        WalletStatus status,
        boolean highContention,
        Instant createdAt,
        Instant updatedAt
) {
    public static WalletResponse from(Wallet wallet) {
        return new WalletResponse(
                wallet.getWalletId(),
                wallet.getUserId(),
                wallet.getCurrency(),
                wallet.getBalance(),
                wallet.getStatus(),
                wallet.isHighContention(),
                wallet.getCreatedAt(),
                wallet.getUpdatedAt()
        );
    }
}
