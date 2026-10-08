package com.paytmmoney.seatreservation.domain;

/**
 * Every expected, non-bug outcome that declines a request. The hierarchy is sealed so the single HTTP mapping in the
 * API layer is an exhaustive switch: adding a new decline without mapping it is a compile error, not a surprise 500.
 */
public abstract sealed class DomainException extends RuntimeException
        permits ShowNotFoundException,
                ReservationNotFoundException,
                SeatTakenException,
                PerUserLimitException,
                IdempotencyKeyReusedException,
                IdempotencyKeyRequiredException,
                IdempotencyKeyMismatchException,
                UnknownSeatException,
                RequestValidationException,
                ContentionException,
                InvalidAdminKeyException,
                InvalidTokenRequestException {

    private static final long serialVersionUID = 1L;

    protected DomainException(String message) {
        // Declines are routine under load: skip the stack trace, it is never logged and costs CPU in a stampede.
        super(message, null, false, false);
    }
}
