package com.paytmmoney.seatreservation.domain;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates a list of seat labels and returns it sorted. Sorting is not cosmetic: it gives the fingerprint its
 * order-insensitivity and is the same order in which seat rows are locked (ADR-006, ADR-017).
 */
public final class SeatSelection {

    /**
     * Row letters then seat number: {@code A1}, {@code AB12}, {@code ZZZ9999}. ASCII only, so Java's natural order
     * equals Postgres {@code COLLATE "C"} order.
     */
    public static final Pattern LABEL = Pattern.compile("^[A-Z]{1,3}[0-9]{1,4}$");

    private SeatSelection() {}

    /** Seats requested in one reservation: non-empty, no nulls, no duplicates, valid labels. */
    public static List<String> forReservation(List<String> seats) {
        return validated("seats", seats, Integer.MAX_VALUE);
    }

    /** Seats of a new show: as above, plus the per-show maximum. */
    public static List<String> forNewShow(List<String> seats) {
        return validated("seats", seats, ShowRules.MAX_SEATS);
    }

    private static List<String> validated(String field, List<String> seats, int max) {
        if (seats == null || seats.isEmpty()) {
            throw new RequestValidationException(field, "must contain at least one seat");
        }
        if (seats.size() > max) {
            throw new RequestValidationException(field, "must contain at most " + max + " seats");
        }
        Set<String> seen = new HashSet<>(seats.size() * 2);
        for (String label : seats) {
            if (label == null || !LABEL.matcher(label).matches()) {
                throw new RequestValidationException(field, "invalid seat label: " + label);
            }
            if (!seen.add(label)) {
                throw new RequestValidationException(field, "duplicate seat: " + label);
            }
        }
        return seats.stream().sorted().toList();
    }
}
