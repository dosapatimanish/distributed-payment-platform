package com.paymentplatform.wallet.exception;

/** The client-generated CIF collided with an existing customer; the client should retry. */
public class CifTakenException extends RuntimeException {

    public CifTakenException(String cif) {
        super("CIF %s is already in use".formatted(cif));
    }
}
