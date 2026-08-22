package com.paymentplatform.ledger.web;

import java.util.List;

public record StatementResponse(
        String walletId,
        List<LedgerEntryResponse> entries
) {
}
