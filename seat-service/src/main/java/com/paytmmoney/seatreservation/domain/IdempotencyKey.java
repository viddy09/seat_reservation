package com.paytmmoney.seatreservation.domain;

import java.util.regex.Pattern;

/** Resolves the idempotency key from the header and/or body field (docs/DESIGN.md A8). */
public final class IdempotencyKey {

    /** Visible ASCII, 1-128 chars: safe to store, compare and log. */
    private static final Pattern FORMAT = Pattern.compile("^[\\x21-\\x7E]{1,128}$");

    private IdempotencyKey() {}

    public static String resolve(String header, String body) {
        boolean hasHeader = header != null && !header.isEmpty();
        boolean hasBody = body != null && !body.isEmpty();
        if (!hasHeader && !hasBody) {
            throw new IdempotencyKeyRequiredException();
        }
        if (hasHeader && hasBody && !header.equals(body)) {
            throw new IdempotencyKeyMismatchException();
        }
        String key = hasHeader ? header : body;
        if (!FORMAT.matcher(key).matches()) {
            throw new RequestValidationException("idempotency_key", "must be 1-128 visible ASCII characters");
        }
        return key;
    }
}
