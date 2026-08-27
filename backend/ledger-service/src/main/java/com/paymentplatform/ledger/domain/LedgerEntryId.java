package com.paymentplatform.ledger.domain;

import java.io.Serializable;
import java.util.Objects;

/** Composite key for {@link LedgerEntry}: the posting's transaction id + the 2-digit leg number. */
public class LedgerEntryId implements Serializable {

    private String transactionId;
    private String entryNo;

    public LedgerEntryId() {
    }

    public LedgerEntryId(String transactionId, String entryNo) {
        this.transactionId = transactionId;
        this.entryNo = entryNo;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof LedgerEntryId other)) return false;
        return Objects.equals(transactionId, other.transactionId) && Objects.equals(entryNo, other.entryNo);
    }

    @Override
    public int hashCode() {
        return Objects.hash(transactionId, entryNo);
    }
}
