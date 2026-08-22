package com.paymentplatform.wallet.exception;

import com.paymentplatform.wallet.domain.ReservationStatus;

public class InvalidReservationStateException extends RuntimeException {

    public InvalidReservationStateException(String reservationId, ReservationStatus current, ReservationStatus expected) {
        super("Reservation %s is %s, expected %s".formatted(reservationId, current, expected));
    }
}
