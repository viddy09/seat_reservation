package com.paytmmoney.seatreservation.domain;

public final class InvalidAdminKeyException extends DomainException {

    private static final long serialVersionUID = 1L;

    public InvalidAdminKeyException() {
        super("Admin tokens require a valid X-Admin-Key header");
    }
}
