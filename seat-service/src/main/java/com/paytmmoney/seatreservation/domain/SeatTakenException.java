package com.paytmmoney.seatreservation.domain;

import java.util.List;

/** At least one requested seat is not available; nothing was taken (all-or-nothing). */
public final class SeatTakenException extends DomainException {

    private static final long serialVersionUID = 1L;

    private final transient List<String> seats;

    public SeatTakenException(List<String> seats) {
        super("Some requested seats are no longer available");
        this.seats = List.copyOf(seats);
    }

    public List<String> seats() {
        return seats;
    }
}
