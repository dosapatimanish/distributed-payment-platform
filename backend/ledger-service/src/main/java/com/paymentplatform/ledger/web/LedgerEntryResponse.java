package com.paymentplatform.ledger.web;

import com.paymentplatform.ledger.domain.EntryType;
import com.paymentplatform.ledger.domain.LedgerEntry;

import java.math.BigDecimal;
import java.time.Instant;

public record LedgerEntryResponse(
        String transactionId,
        String entryNo,
        String accountNo,
        EntryType entryType,
        BigDecimal amount,
        String currency,
        BigDecimal balanceAfter,
        Instant createdAt
) {
    public static LedgerEntryResponse from(LedgerEntry entry) {
        return new LedgerEntryResponse(
                entry.getTransactionId(),
                entry.getEntryNo(),
                entry.getAccountNo(),
                entry.getEntryType(),
                entry.getAmount(),
                entry.getCurrency(),
                entry.getBalanceAfter(),
                entry.getCreatedAt()
        );
    }
}
