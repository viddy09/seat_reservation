package com.paytmmoney.seatreservation.domain;

import java.util.List;

public final class UnknownSeatException extends DomainException {

    private static final long serialVersionUID = 1L;

    private final transient List<String> seats;

    public UnknownSeatException(List<String> seats) {
        super("Some requested seats do not exist in this show");
        this.seats = List.copyOf(seats);
    }

    public List<String> seats() {
        return seats;
    }
}
