package com.paytmmoney.seatreservation.api;

import com.paytmmoney.seatreservation.domain.Reservation;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ReservationResponse(
        UUID reservationId,
        UUID showId,
        String userId,
        List<String> seats,
        long amountPaise,
        String status,
        Instant createdAt,
        Instant updatedAt) {

    static ReservationResponse from(Reservation reservation) {
        return new ReservationResponse(
                reservation.id(),
                reservation.showId(),
                reservation.userId(),
                reservation.seats(),
                reservation.amountPaise(),
                reservation.status().dbValue(),
                reservation.createdAt(),
                reservation.updatedAt());
    }
}
