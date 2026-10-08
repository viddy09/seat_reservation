package com.paytmmoney.seatreservation.domain;

import java.util.Map;

/** Well-formed JSON that breaks a business rule (422). {@code details} names the offending fields. */
public final class RequestValidationException extends DomainException {

    private static final long serialVersionUID = 1L;

    private final transient Map<String, Object> details;

    public RequestValidationException(String field, String problem) {
        super("Request validation failed");
        this.details = Map.of(field, problem);
    }

    public Map<String, Object> details() {
        return details;
    }
}
