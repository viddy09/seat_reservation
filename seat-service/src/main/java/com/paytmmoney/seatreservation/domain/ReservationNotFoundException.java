package com.paytmmoney.seatreservation.domain;

/** Also used when the reservation exists but belongs to someone else, so existence never leaks (ADR-016). */
public final class ReservationNotFoundException extends DomainException {

    private static final long serialVersionUID = 1L;

    public ReservationNotFoundException() {
        super("Reservation not found");
    }
}
