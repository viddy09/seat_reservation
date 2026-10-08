package com.paytmmoney.seatreservation.domain;

/** A malformed {@code POST /auth/token} request (400, as the endpoint table specifies). */
public final class InvalidTokenRequestException extends DomainException {

    private static final long serialVersionUID = 1L;

    public InvalidTokenRequestException(String message) {
        super(message);
    }
}
