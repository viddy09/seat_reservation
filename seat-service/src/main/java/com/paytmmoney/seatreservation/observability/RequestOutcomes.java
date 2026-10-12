package com.paytmmoney.seatreservation.observability;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;

/**
 * Whoever produces a response records its outcome here (a controller on success, an error handler with the error
 * code); the observability filter reads it once the response is complete and turns it into metrics and the access
 * log line. One request attribute, so metrics and logs can never disagree about what happened.
 */
public final class RequestOutcomes {

    public static final String CONFIRMED = "confirmed";
    public static final String IDEMPOTENT_REPLAY = "idempotent_replay";
    public static final String CANCELLED = "cancelled";
    public static final String ALREADY_CANCELLED = "already_cancelled";

    private static final String OUTCOME = RequestOutcomes.class.getName() + ".outcome";
    private static final String RESERVATION_ID = RequestOutcomes.class.getName() + ".reservationId";

    private RequestOutcomes() {}

    /** A success outcome (see constants) or an error code from the error envelope. */
    public static void record(HttpServletRequest request, String outcome) {
        request.setAttribute(OUTCOME, outcome);
    }

    public static void recordReservation(HttpServletRequest request, String outcome, Object reservationId) {
        record(request, outcome);
        request.setAttribute(RESERVATION_ID, String.valueOf(reservationId));
    }

    static Optional<String> outcome(HttpServletRequest request) {
        return Optional.ofNullable((String) request.getAttribute(OUTCOME));
    }

    static Optional<String> reservationId(HttpServletRequest request) {
        return Optional.ofNullable((String) request.getAttribute(RESERVATION_ID));
    }
}
