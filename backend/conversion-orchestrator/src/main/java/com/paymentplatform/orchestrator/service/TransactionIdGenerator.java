package com.paymentplatform.orchestrator.service;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Builds a 16-char transaction id:
 * {@code [source-currency short_code 2][business date YYYYMMDD 8][daily sequence 6]}
 * (backend-documents/identifier-scheme.md). Caps at 999 999 transactions per business date.
 */
@Component
public class TransactionIdGenerator {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final CurrencyCache currencyCache;
    private final BusinessDateService businessDateService;
    private final TransactionSequenceService transactionSequenceService;

    public TransactionIdGenerator(CurrencyCache currencyCache,
                                   BusinessDateService businessDateService,
                                   TransactionSequenceService transactionSequenceService) {
        this.currencyCache = currencyCache;
        this.businessDateService = businessDateService;
        this.transactionSequenceService = transactionSequenceService;
    }

    public String next(String sourceCurrencyCode) {
        String shortCode = currencyCache.shortCode(sourceCurrencyCode);
        LocalDate businessDate = businessDateService.current();
        long seq = transactionSequenceService.next(businessDate);
        return shortCode + businessDate.format(DATE) + String.format("%06d", seq);
    }
}
