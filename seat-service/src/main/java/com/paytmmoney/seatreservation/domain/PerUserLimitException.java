package com.paytmmoney.seatreservation.domain;

public final class PerUserLimitException extends DomainException {

    private static final long serialVersionUID = 1L;

    private final int limit;

    public PerUserLimitException(int limit) {
        super("Request would exceed the per-user seat limit of " + limit + " for this show");
        this.limit = limit;
    }

    public int limit() {
        return limit;
    }
}
