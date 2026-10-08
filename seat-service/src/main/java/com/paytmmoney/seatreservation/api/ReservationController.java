package com.paytmmoney.seatreservation.api;

import com.paytmmoney.seatreservation.security.CurrentUser;
import com.paytmmoney.seatreservation.service.ReservationService;
import com.paytmmoney.seatreservation.service.ReservationService.ReserveOutcome;
import com.paytmmoney.seatreservation.service.ReservationService.UserReserveRequest;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ReservationController {

    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private final ReservationService reservations;
    private final CurrentUser currentUser;

    public ReservationController(ReservationService reservations, CurrentUser currentUser) {
        this.reservations = reservations;
        this.currentUser = currentUser;
    }

    /**
     * 201 for a new reservation. A replay of the same key and body returns 200 with the original reservation and
     * {@code Idempotent-Replayed: true}, so it can never be mistaken for a second confirmation (ADR-009).
     */
    @PostMapping("/shows/{showId}/reserve")
    public ResponseEntity<ReservationResponse> reserve(
            @PathVariable UUID showId,
            @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) String headerKey,
            @RequestBody ReserveRequest body) {
        ReserveOutcome outcome = reservations.reserve(
                new UserReserveRequest(showId, currentUser.id(), headerKey, body.idempotencyKey(), body.seats()));
        ReservationResponse response = ReservationResponse.from(outcome.reservation());
        if (outcome.replayed()) {
            return ResponseEntity.ok().header(REPLAYED_HEADER, "true").body(response);
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .location(URI.create("/reservations/" + response.reservationId()))
                .body(response);
    }

    @GetMapping("/reservations/{reservationId}")
    public ReservationResponse get(@PathVariable UUID reservationId) {
        return ReservationResponse.from(reservations.get(reservationId, currentUser.id()));
    }

    /** There is deliberately no user_id here: identity comes only from the token, and unknown fields are ignored. */
    public record ReserveRequest(List<String> seats, String idempotencyKey) {}
}
