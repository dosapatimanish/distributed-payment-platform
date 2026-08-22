package com.paymentplatform.ledger.exception;

/**
 * Thrown when a posting is attempted for a {@code transactionId} that already has ledger entries.
 * Ledger rows are append-only (design doc §6.1.5) - a correction must be posted as a new,
 * offsetting {@code transactionId} (e.g. a {@code -reversal} suffix), never a second posting
 * against the original one.
 */
public class LedgerConflictException extends RuntimeException {

    public LedgerConflictException(String transactionId) {
        super("Ledger entries already exist for transaction " + transactionId + " - post a new offsetting transaction instead");
    }
}
