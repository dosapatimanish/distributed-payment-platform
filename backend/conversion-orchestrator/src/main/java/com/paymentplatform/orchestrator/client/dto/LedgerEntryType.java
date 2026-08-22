package com.paymentplatform.orchestrator.client.dto;

/** Local copy of ledger-service's {@code EntryType} - no shared library between services, see implementation notes. */
public enum LedgerEntryType {
    DEBIT,
    CREDIT
}
