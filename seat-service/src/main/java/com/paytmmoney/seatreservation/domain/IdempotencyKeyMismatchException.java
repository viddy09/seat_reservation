package com.paytmmoney.seatreservation.domain;

public final class IdempotencyKeyMismatchException extends DomainException {

    private static final long serialVersionUID = 1L;

    public IdempotencyKeyMismatchException() {
        super("Idempotency-Key header and idempotency_key field differ");
    }
}
