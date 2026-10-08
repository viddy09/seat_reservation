package com.paytmmoney.seatreservation.api;

import com.paytmmoney.seatreservation.domain.ContentionException;
import com.paytmmoney.seatreservation.domain.DomainException;
import com.paytmmoney.seatreservation.domain.IdempotencyKeyMismatchException;
import com.paytmmoney.seatreservation.domain.IdempotencyKeyRequiredException;
import com.paytmmoney.seatreservation.domain.IdempotencyKeyReusedException;
import com.paytmmoney.seatreservation.domain.InvalidAdminKeyException;
import com.paytmmoney.seatreservation.domain.InvalidTokenRequestException;
import com.paytmmoney.seatreservation.domain.PerUserLimitException;
import com.paytmmoney.seatreservation.domain.RequestValidationException;
import com.paytmmoney.seatreservation.domain.ReservationNotFoundException;
import com.paytmmoney.seatreservation.domain.SeatTakenException;
import com.paytmmoney.seatreservation.domain.ShowNotFoundException;
import com.paytmmoney.seatreservation.domain.UnknownSeatException;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * Domain decline → HTTP status and {@code error.code}. An exhaustive switch over the sealed hierarchy: a new decline
 * type that isn't mapped here fails compilation.
 */
final class DomainErrorMapping {

    static final int RETRY_AFTER_SECONDS = 1;

    record Mapped(HttpStatus status, String code, Map<String, Object> details, int retryAfterSeconds) {

        Mapped(HttpStatus status, String code) {
            this(status, code, Map.of(), 0);
        }
    }

    private DomainErrorMapping() {}

    static Mapped map(DomainException ex) {
        return switch (ex) {
            case ShowNotFoundException e -> new Mapped(HttpStatus.NOT_FOUND, ErrorCodes.NOT_FOUND);
            case ReservationNotFoundException e -> new Mapped(HttpStatus.NOT_FOUND, ErrorCodes.NOT_FOUND);
            case SeatTakenException e ->
                new Mapped(HttpStatus.CONFLICT, ErrorCodes.SEAT_TAKEN, Map.of("seats", e.seats()), 0);
            case PerUserLimitException e ->
                new Mapped(HttpStatus.CONFLICT, ErrorCodes.PER_USER_LIMIT, Map.of("limit", e.limit()), 0);
            case IdempotencyKeyReusedException e -> new Mapped(HttpStatus.CONFLICT, ErrorCodes.IDEMPOTENCY_KEY_REUSED);
            case ContentionException e ->
                new Mapped(HttpStatus.CONFLICT, ErrorCodes.CONTENTION, Map.of(), RETRY_AFTER_SECONDS);
            case IdempotencyKeyRequiredException e ->
                new Mapped(HttpStatus.BAD_REQUEST, ErrorCodes.IDEMPOTENCY_KEY_REQUIRED);
            case InvalidTokenRequestException e -> new Mapped(HttpStatus.BAD_REQUEST, ErrorCodes.INVALID_REQUEST);
            case IdempotencyKeyMismatchException e ->
                new Mapped(HttpStatus.UNPROCESSABLE_CONTENT, ErrorCodes.IDEMPOTENCY_KEY_MISMATCH);
            case UnknownSeatException e ->
                new Mapped(HttpStatus.UNPROCESSABLE_CONTENT, ErrorCodes.UNKNOWN_SEAT, Map.of("seats", e.seats()), 0);
            case RequestValidationException e ->
                new Mapped(HttpStatus.UNPROCESSABLE_CONTENT, ErrorCodes.VALIDATION, e.details(), 0);
            case InvalidAdminKeyException e -> new Mapped(HttpStatus.UNAUTHORIZED, ErrorCodes.INVALID_ADMIN_KEY);
        };
    }
}
