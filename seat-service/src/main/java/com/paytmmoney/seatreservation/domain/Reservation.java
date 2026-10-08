package com.paytmmoney.seatreservation.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record Reservation(
        UUID id,
        UUID showId,
        String userId,
        String idempotencyKey,
        RequestFingerprint fingerprint,
        List<String> seats,
        long amountPaise,
        ReservationStatus status,
        Instant createdAt,
        Instant updatedAt) {

    public Reservation {
        seats = List.copyOf(seats);
    }
}
