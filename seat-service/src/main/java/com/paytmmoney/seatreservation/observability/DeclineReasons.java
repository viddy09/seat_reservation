package com.paytmmoney.seatreservation.observability;

import org.springframework.http.HttpStatus;

/** Collapses error-envelope codes into the bounded {@code reason} label of the declined counter. */
final class DeclineReasons {

    private DeclineReasons() {}

    static String forErrorCode(String code, int status) {
        if (code == null) {
            return forStatus(status);
        }
        return switch (code) {
            case "seat_taken", "per_user_limit", "idempotency_key_reused", "contention", "server_busy" -> code;
            case "unauthorized", "forbidden", "invalid_admin_key" -> "unauthorized";
            case "not_found" -> "not_found";
            default -> "validation";
        };
    }

    private static String forStatus(int status) {
        if (status == HttpStatus.UNAUTHORIZED.value() || status == HttpStatus.FORBIDDEN.value()) {
            return "unauthorized";
        }
        if (status == HttpStatus.NOT_FOUND.value()) {
            return "not_found";
        }
        if (status == HttpStatus.TOO_MANY_REQUESTS.value()) {
            return "server_busy";
        }
        return "validation";
    }
}
