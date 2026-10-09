package com.paytmmoney.seatreservation.domain;

import java.util.List;

/** Raw numbers from one snapshot, and the checks the assignment requires (reconcile endpoint, I3). */
public record ReconcileNumbers(
        int totalSeats,
        int available,
        int held,
        int confirmed,
        int reservedSeats,
        int countedSeats,
        int usersOverLimit,
        int maxSeatsPerUser,
        int perUserLimit,
        int duplicateSeats,
        int orphanSeats) {

    public record Check(String name, boolean ok, String detail) {}

    public List<Check> checks() {
        int taken = held + confirmed;
        return List.of(
                new Check(
                        "counts_sum_to_total",
                        available + held + confirmed == totalSeats,
                        available + " + " + held + " + " + confirmed + " == " + totalSeats),
                new Check(
                        "taken_seats_match_reservations",
                        taken == reservedSeats,
                        taken + " taken seats == " + reservedSeats + " seats in confirmed reservations"),
                new Check(
                        "user_counts_match_taken_seats",
                        countedSeats == taken,
                        "sum(user_show_counts) " + countedSeats + " == " + taken),
                new Check(
                        "no_user_over_limit",
                        usersOverLimit == 0,
                        "max per user " + maxSeatsPerUser + " <= limit " + perUserLimit),
                new Check(
                        "no_seat_in_two_active_reservations",
                        duplicateSeats == 0,
                        duplicateSeats + " seats listed by more than one confirmed reservation"),
                new Check(
                        "every_taken_seat_has_its_reservation",
                        orphanSeats == 0,
                        orphanSeats + " taken seats without a matching confirmed reservation"));
    }

    public boolean ok() {
        return checks().stream().allMatch(Check::ok);
    }
}
