package com.paymentplatform.orchestrator.client.dto;

import java.util.List;

/** Local copy of ledger-service's {@code PostEntriesRequest} shape - no shared library between services, see implementation notes. */
public record PostEntriesRequest(String transactionId, List<LedgerLineRequest> entries) {
}
