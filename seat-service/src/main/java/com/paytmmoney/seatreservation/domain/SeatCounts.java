package com.paytmmoney.seatreservation.domain;

import java.util.List;

/** {@code available + held + confirmed == total}; derived from one snapshot so it holds by construction (I3). */
public record SeatCounts(int total, int available, int held, int confirmed) {

    public static SeatCounts of(List<Seat> seats) {
        int available = 0;
        int held = 0;
        int confirmed = 0;
        for (Seat seat : seats) {
            switch (seat.status()) {
                case AVAILABLE -> available++;
                case HELD -> held++;
                case CONFIRMED -> confirmed++;
            }
        }
        return new SeatCounts(seats.size(), available, held, confirmed);
    }
}
