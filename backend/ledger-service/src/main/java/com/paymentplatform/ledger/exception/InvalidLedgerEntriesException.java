package com.paymentplatform.ledger.exception;

/** Thrown by {@code DoubleEntryValidator} when a posting fails the double-entry invariant. */
public class InvalidLedgerEntriesException extends RuntimeException {

    public InvalidLedgerEntriesException(String message) {
        super(message);
    }
}
