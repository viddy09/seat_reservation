package com.paytmmoney.seatreservation.domain;

public final class IdempotencyKeyRequiredException extends DomainException {

    private static final long serialVersionUID = 1L;

    public IdempotencyKeyRequiredException() {
        super("An idempotency key is required (Idempotency-Key header or idempotency_key field)");
    }
}
