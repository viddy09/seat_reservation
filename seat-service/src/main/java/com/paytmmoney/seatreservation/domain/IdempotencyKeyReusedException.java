package com.paytmmoney.seatreservation.domain;

/** The caller's key is already bound to a reservation made with a different request body. */
public final class IdempotencyKeyReusedException extends DomainException {

    private static final long serialVersionUID = 1L;

    public IdempotencyKeyReusedException() {
        super("Idempotency key was already used with a different request");
    }
}
