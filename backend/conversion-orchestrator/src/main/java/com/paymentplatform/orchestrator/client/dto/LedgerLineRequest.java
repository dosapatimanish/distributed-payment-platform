package com.paymentplatform.orchestrator.client.dto;

import java.math.BigDecimal;

/** Local copy of ledger-service's {@code LedgerLineRequest} shape - no shared library between services, see implementation notes. */
public record LedgerLineRequest(String accountNo, LedgerEntryType entryType, BigDecimal amount, String currency, BigDecimal balanceAfter) {
}
