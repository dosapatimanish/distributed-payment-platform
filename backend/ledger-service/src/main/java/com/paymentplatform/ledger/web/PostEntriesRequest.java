package com.paymentplatform.ledger.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * One double-entry posting (design doc §6.3.5 {@code postEntries}) - all lines for one {@code
 * transactionId}, written atomically. See {@code DoubleEntryValidator} for the netting rules
 * enforced before any row is persisted.
 */
public record PostEntriesRequest(
        @NotBlank String transactionId,
        @NotEmpty @Valid List<LedgerLineRequest> entries
) {
}
