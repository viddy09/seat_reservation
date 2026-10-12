package com.paytmmoney.seatreservation.burst;

import java.util.List;
import java.util.stream.IntStream;

/**
 * Seat labels matching the service's pattern: rows of 100 seats, row letters A..Z then AA, AB, ... ({@code A1},
 * {@code A100}, {@code B1}, ..., {@code AA1}).
 */
final class Seats {

    private static final int SEATS_PER_ROW = 100;
    private static final int LETTERS = 26;

    private Seats() {}

    static List<String> labels(int count) {
        return IntStream.range(0, count).mapToObj(Seats::label).toList();
    }

    static String label(int index) {
        return row(index / SEATS_PER_ROW) + (index % SEATS_PER_ROW + 1);
    }

    /** Bijective base-26: 0 -> A, 25 -> Z, 26 -> AA. */
    private static String row(int index) {
        StringBuilder letters = new StringBuilder();
        int n = index + 1;
        while (n > 0) {
            int rem = (n - 1) % LETTERS;
            letters.insert(0, (char) ('A' + rem));
            n = (n - 1) / LETTERS;
        }
        return letters.toString();
    }
}
