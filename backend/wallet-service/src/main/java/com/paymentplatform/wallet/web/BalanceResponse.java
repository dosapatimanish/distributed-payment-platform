package com.paymentplatform.wallet.web;

import com.paymentplatform.wallet.domain.Wallet;
import com.paymentplatform.wallet.domain.WalletStatus;

import java.math.BigDecimal;

public record BalanceResponse(
        String accountNo,
        String currency,
        BigDecimal balance,
        WalletStatus status
) {
    public static BalanceResponse from(Wallet wallet) {
        return new BalanceResponse(wallet.getAccountNo(), wallet.getCurrency(), wallet.getBalance(), wallet.getStatus());
    }
}
