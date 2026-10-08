package com.paytmmoney.seatreservation.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Database-level invariant checks run after every concurrency test (I1, I3, I5). Phase 3 adds the same checks as the
 * public reconcile endpoint.
 */
public final class Invariants {

    private Invariants() {}

    public static void assertHold(JdbcClient jdbc, UUID showId) {
        // I3: every seat is in exactly one state, and the states add up to the total.
        Integer total = jdbc.sql("SELECT total_seats FROM shows WHERE id = ?")
                .param(showId)
                .query(Integer.class)
                .single();
        Integer counted = jdbc.sql("SELECT count(*) FROM seats WHERE show_id = ?")
                .param(showId)
                .query(Integer.class)
                .single();
        assertThat(counted).as("available + held + confirmed == total_seats").isEqualTo(total);

        Integer confirmedSeats =
                count(jdbc, "SELECT count(*) FROM seats WHERE show_id = ? AND status = 'confirmed'", showId);
        Integer reservedSeats = count(
                jdbc,
                "SELECT coalesce(sum(cardinality(seats)), 0) FROM reservations WHERE show_id = ? AND status = 'confirmed'",
                showId);
        assertThat(confirmedSeats)
                .as("confirmed seats == seats of confirmed reservations")
                .isEqualTo(reservedSeats);

        Integer counterSum =
                count(jdbc, "SELECT coalesce(sum(seat_count), 0) FROM user_show_counts WHERE show_id = ?", showId);
        assertThat(counterSum).as("sum(user_show_counts) == confirmed seats").isEqualTo(confirmedSeats);

        // I1: every confirmed seat points at a confirmed reservation that lists it, owned by the same user.
        Integer orphaned = count(jdbc, """
                SELECT count(*) FROM seats s
                LEFT JOIN reservations r
                  ON r.id = s.reservation_id AND r.status = 'confirmed' AND r.user_id = s.user_id
                 AND s.seat_label = ANY(r.seats)
                WHERE s.show_id = ? AND s.status = 'confirmed' AND r.id IS NULL
                """, showId);
        assertThat(orphaned)
                .as("confirmed seats without a matching reservation")
                .isZero();

        // I1: no seat label appears in two confirmed reservations.
        Integer doubleSold = count(jdbc, """
                SELECT count(*) FROM (
                  SELECT label FROM reservations r, unnest(r.seats) AS label
                  WHERE r.show_id = ? AND r.status = 'confirmed'
                  GROUP BY label HAVING count(*) > 1) dup
                """, showId);
        assertThat(doubleSold)
                .as("seats referenced by two confirmed reservations")
                .isZero();

        // I5: nobody holds more than the limit.
        Integer overLimit = count(jdbc, """
                SELECT count(*) FROM user_show_counts c JOIN shows s ON s.id = c.show_id
                WHERE c.show_id = ? AND c.seat_count > s.per_user_limit
                """, showId);
        assertThat(overLimit).as("users over the per-user limit").isZero();
    }

    private static Integer count(JdbcClient jdbc, String sql, UUID showId) {
        return jdbc.sql(sql).param(showId).query(Integer.class).single();
    }
}
