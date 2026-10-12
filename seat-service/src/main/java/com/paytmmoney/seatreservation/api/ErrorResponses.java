package com.paytmmoney.seatreservation.api;

import com.paytmmoney.seatreservation.observability.RequestOutcomes;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;

/**
 * Builds the envelope for a request and records its error code as the request outcome, so the access log and the
 * decline metrics see exactly what the client saw. Every error path goes through here.
 */
final class ErrorResponses {

    private ErrorResponses() {}

    static ErrorResponse of(HttpServletRequest request, String code, String message, Map<String, Object> details) {
        if (request == null) {
            return ErrorResponse.of(code, message, details, null);
        }
        RequestOutcomes.record(request, code);
        return ErrorResponse.of(code, message, details, RequestIdFilter.current(request));
    }
}
