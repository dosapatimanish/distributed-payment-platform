package com.paymentplatform.ledger.web;

import java.util.List;

public record StatementResponse(
        String accountNo,
        List<LedgerEntryResponse> entries
) {
}
