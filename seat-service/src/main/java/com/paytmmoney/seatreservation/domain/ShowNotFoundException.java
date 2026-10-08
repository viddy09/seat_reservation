package com.paytmmoney.seatreservation.domain;

public final class ShowNotFoundException extends DomainException {

    private static final long serialVersionUID = 1L;

    public ShowNotFoundException() {
        super("Show not found");
    }
}
