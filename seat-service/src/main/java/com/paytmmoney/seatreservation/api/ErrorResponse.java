package com.paytmmoney.seatreservation.api;

import java.util.Map;

/**
 * The one error envelope used for every error response:
 * {@code {"error":{"code":"seat_taken","message":"...","details":{...}},"request_id":"..."}}.
 */
public record ErrorResponse(Body error, String requestId) {

    public record Body(String code, String message, Map<String, Object> details) {}

    public static ErrorResponse of(String code, String message, Map<String, Object> details, String requestId) {
        return new ErrorResponse(new Body(code, message, details == null ? Map.of() : Map.copyOf(details)), requestId);
    }
}
