package com.paymentplatform.ledger.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;

import java.util.List;

/**
 * One double-entry posting (design doc §6.3.5 {@code postEntries}) - all lines for one {@code
 * transactionId}, written atomically. {@code transactionId} is a 16-digit id, optionally with a
 * {@code -reversal} suffix for a compensation posting. See {@code DoubleEntryValidator} for the
 * netting rules enforced before any row is persisted.
 */
public record PostEntriesRequest(
        @NotBlank
        @Pattern(regexp = "\\d{16}(-reversal)?", message = "must be a 16-digit transaction id, optionally suffixed -reversal")
        String transactionId,
        @NotEmpty @Valid List<LedgerLineRequest> entries
) {
}
