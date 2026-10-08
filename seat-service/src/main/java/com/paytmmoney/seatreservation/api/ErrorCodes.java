package com.paytmmoney.seatreservation.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

/** Stable, machine-readable {@code error.code} values for errors that are not domain declines. */
final class ErrorCodes {

    static final String INVALID_JSON = "invalid_json";
    static final String INVALID_REQUEST = "invalid_request";
    static final String VALIDATION = "validation";
    static final String UNAUTHORIZED = "unauthorized";
    static final String FORBIDDEN = "forbidden";
    static final String NOT_FOUND = "not_found";
    static final String METHOD_NOT_ALLOWED = "method_not_allowed";
    static final String NOT_ACCEPTABLE = "not_acceptable";
    static final String UNSUPPORTED_MEDIA_TYPE = "unsupported_media_type";
    static final String INTERNAL_ERROR = "internal_error";

    static final String SEAT_TAKEN = "seat_taken";
    static final String PER_USER_LIMIT = "per_user_limit";
    static final String IDEMPOTENCY_KEY_REUSED = "idempotency_key_reused";
    static final String IDEMPOTENCY_KEY_REQUIRED = "idempotency_key_required";
    static final String IDEMPOTENCY_KEY_MISMATCH = "idempotency_key_mismatch";
    static final String UNKNOWN_SEAT = "unknown_seat";
    static final String CONTENTION = "contention";
    static final String INVALID_ADMIN_KEY = "invalid_admin_key";
    static final String SERVER_BUSY = "server_busy";
    static final String SERVICE_UNAVAILABLE = "service_unavailable";

    private ErrorCodes() {}

    static String forStatus(HttpStatusCode status) {
        HttpStatus known = HttpStatus.resolve(status.value());
        if (known == null) {
            return status.is5xxServerError() ? INTERNAL_ERROR : INVALID_REQUEST;
        }
        return switch (known) {
            case UNAUTHORIZED -> UNAUTHORIZED;
            case FORBIDDEN -> FORBIDDEN;
            case NOT_FOUND -> NOT_FOUND;
            case METHOD_NOT_ALLOWED -> METHOD_NOT_ALLOWED;
            case NOT_ACCEPTABLE -> NOT_ACCEPTABLE;
            case UNSUPPORTED_MEDIA_TYPE -> UNSUPPORTED_MEDIA_TYPE;
            case UNPROCESSABLE_CONTENT -> VALIDATION;
            default -> status.is5xxServerError() ? INTERNAL_ERROR : INVALID_REQUEST;
        };
    }

    static String messageFor(HttpStatusCode status) {
        HttpStatus known = HttpStatus.resolve(status.value());
        return known != null ? known.getReasonPhrase() : "Error";
    }
}
