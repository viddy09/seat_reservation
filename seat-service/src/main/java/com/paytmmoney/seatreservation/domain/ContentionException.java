package com.paytmmoney.seatreservation.domain;

/**
 * Deadlock/serialization retries were exhausted (ADR-013). With the global lock order this should never happen; it is
 * a 409 rather than a 5xx because the database is healthy and nothing was written.
 */
public final class ContentionException extends DomainException {

    private static final long serialVersionUID = 1L;

    public ContentionException() {
        super("Request could not be completed due to contention; retry shortly");
    }
}
