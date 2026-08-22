package com.paymentplatform.ledger.web;

import com.paymentplatform.ledger.domain.EntryType;
import com.paymentplatform.ledger.domain.LedgerEntry;

import java.math.BigDecimal;
import java.time.Instant;

public record LedgerEntryResponse(
        String entryId,
        String transactionId,
        String walletId,
        EntryType entryType,
        BigDecimal amount,
        String currency,
        BigDecimal balanceAfter,
        Instant createdAt
) {
    public static LedgerEntryResponse from(LedgerEntry entry) {
        return new LedgerEntryResponse(
                entry.getEntryId(),
                entry.getTransactionId(),
                entry.getWalletId(),
                entry.getEntryType(),
                entry.getAmount(),
                entry.getCurrency(),
                entry.getBalanceAfter(),
                entry.getCreatedAt()
        );
    }
}
