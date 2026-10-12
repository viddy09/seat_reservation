package com.paytmmoney.seatreservation.burst;

import java.util.List;

/** One reserve or cancel request and what came back. {@code hotSeat} is set for hot-seat storm requests only. */
record Recorded(Scenario scenario, Kind kind, List<String> seats, String hotSeat, Api.Response response) {

    enum Kind {
        RESERVE,
        CANCEL
    }

    enum Scenario {
        HOT_SEAT_STORM,
        STAMPEDE,
        IDEMPOTENT_RETRY,
        KEY_REUSE,
        PER_USER_LIMIT,
        SPOOF,
        CANCEL_REBOOK
    }

    /** The decline reason the server should have counted, derived from the response exactly as the server does. */
    String declineReason() {
        int status = response.status();
        if (status == Http.OK) {
            return "idempotent_replay";
        }
        String code = response.errorCode();
        return switch (code) {
            case "seat_taken", "per_user_limit", "idempotency_key_reused", "contention", "server_busy" -> code;
            case "unauthorized", "forbidden", "invalid_admin_key" -> "unauthorized";
            case "not_found" -> "not_found";
            default ->
                status == Http.UNAUTHORIZED || status == Http.FORBIDDEN
                        ? "unauthorized"
                        : status == Http.NOT_FOUND ? "not_found" : "validation";
        };
    }
}
