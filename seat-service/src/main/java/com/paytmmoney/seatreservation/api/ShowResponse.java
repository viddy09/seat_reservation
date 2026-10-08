package com.paytmmoney.seatreservation.api;

import com.paytmmoney.seatreservation.domain.SeatCounts;
import com.paytmmoney.seatreservation.service.ShowService.ShowView;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ShowResponse(
        UUID id,
        String name,
        long pricePaise,
        int perUserLimit,
        int totalSeats,
        Instant createdAt,
        Counts counts,
        List<SeatResponse> seats) {

    public record Counts(int total, int available, int held, int confirmed) {}

    public record SeatResponse(String label, String status) {}

    static ShowResponse from(ShowView view) {
        SeatCounts c = view.counts();
        return new ShowResponse(
                view.show().id(),
                view.show().name(),
                view.show().pricePaise(),
                view.show().perUserLimit(),
                view.show().totalSeats(),
                view.show().createdAt(),
                new Counts(c.total(), c.available(), c.held(), c.confirmed()),
                view.seats().stream()
                        .map(seat ->
                                new SeatResponse(seat.label(), seat.status().dbValue()))
                        .toList());
    }
}
