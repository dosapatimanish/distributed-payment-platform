package com.paymentplatform.ledger.web;

import com.paymentplatform.ledger.domain.EntryType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** One leg (DEBIT or CREDIT side, against one account) of a {@link PostEntriesRequest}. */
public record LedgerLineRequest(
        @NotBlank
        @Pattern(regexp = "\\d{12}|SC\\d{10}", message = "must be a 12-digit account number or an SC-prefixed system account")
        String accountNo,
        @NotNull EntryType entryType,
        @NotNull @DecimalMin(value = "0.0001") BigDecimal amount,
        @NotBlank @Size(min = 3, max = 3) String currency,
        @NotNull @DecimalMin(value = "0.0000") BigDecimal balanceAfter
) {
}
