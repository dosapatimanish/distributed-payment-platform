package com.paymentplatform.ledger.service;

import com.paymentplatform.ledger.domain.EntryType;
import com.paymentplatform.ledger.exception.InvalidLedgerEntriesException;
import com.paymentplatform.ledger.web.LedgerLineRequest;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Enforces the double-entry invariant from design doc §6.1.5: "entries for a given
 * transaction_id always net to zero across the involved wallets".
 *
 * <p><b>Scope note (current limitation):</b> "net to zero" only makes sense within a single unit
 * of account, so this validator groups lines by currency and requires each currency group's DEBIT
 * total to equal its CREDIT total. That's exactly right for a same-currency wallet-to-wallet
 * posting (a debit leg and a credit leg in the same currency). It does <b>not</b> yet handle an
 * FX-conversion posting, where the debit leg (source currency) and credit leg (destination
 * currency) are in different currencies and therefore can never net against each other without an
 * explicit FX clearing/suspense leg. This service is standalone for now (not wired into
 * conversion-orchestrator's saga yet - see implementation notes); when that wiring happens, this
 * validator will need either a clearing-account leg or a relaxed cross-currency rule.
 */
@Component
public class DoubleEntryValidator {

    public void validate(List<LedgerLineRequest> entries) {
        if (entries == null || entries.isEmpty()) {
            throw new InvalidLedgerEntriesException("A posting must contain at least one entry");
        }
        boolean hasDebit = entries.stream().anyMatch(e -> e.entryType() == EntryType.DEBIT);
        boolean hasCredit = entries.stream().anyMatch(e -> e.entryType() == EntryType.CREDIT);
        if (!hasDebit || !hasCredit) {
            throw new InvalidLedgerEntriesException("A posting must contain at least one DEBIT and one CREDIT entry");
        }

        Map<String, List<LedgerLineRequest>> byCurrency = entries.stream()
                .collect(Collectors.groupingBy(LedgerLineRequest::currency));

        for (Map.Entry<String, List<LedgerLineRequest>> group : byCurrency.entrySet()) {
            BigDecimal debitTotal = sum(group.getValue(), EntryType.DEBIT);
            BigDecimal creditTotal = sum(group.getValue(), EntryType.CREDIT);
            if (debitTotal.compareTo(creditTotal) != 0) {
                throw new InvalidLedgerEntriesException(
                        "Entries for currency %s do not net to zero: debit=%s credit=%s"
                                .formatted(group.getKey(), debitTotal, creditTotal));
            }
        }
    }

    private BigDecimal sum(List<LedgerLineRequest> lines, EntryType type) {
        return lines.stream()
                .filter(e -> e.entryType() == type)
                .map(LedgerLineRequest::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
