package com.paytmmoney.seatreservation.persistence;

import com.paytmmoney.seatreservation.domain.ReconcileNumbers;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Raw numbers for the reconcile checks, deliberately in ONE statement: Postgres gives a single statement one snapshot
 * even under READ COMMITTED, so numbers read while a burst commits in parallel are mutually consistent and a check
 * can't fail spuriously. Separate queries could each see a different commit and report a false mismatch.
 */
@Repository
public class ReconcileRepository {

    private final JdbcClient jdbc;

    public ReconcileRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ReconcileNumbers> numbers(UUID showId) {
        return jdbc.sql("""
                        WITH seat_states AS (
                            SELECT count(*) FILTER (WHERE status = 'available') AS available,
                                   count(*) FILTER (WHERE status = 'held')      AS held,
                                   count(*) FILTER (WHERE status = 'confirmed') AS confirmed
                            FROM seats WHERE show_id = :show),
                        active AS (
                            SELECT id, user_id, seats FROM reservations
                            WHERE show_id = :show AND status = 'confirmed'),
                        reserved AS (
                            SELECT coalesce(sum(cardinality(seats)), 0) AS seats FROM active),
                        counters AS (
                            SELECT coalesce(sum(seat_count), 0) AS total,
                                   count(*) FILTER (WHERE seat_count > s.per_user_limit) AS over_limit,
                                   coalesce(max(seat_count), 0) AS max_per_user
                            FROM user_show_counts c JOIN shows s ON s.id = c.show_id
                            WHERE c.show_id = :show),
                        duplicates AS (
                            SELECT count(*) AS seats FROM (
                                SELECT label FROM active, unnest(active.seats) AS label
                                GROUP BY label HAVING count(*) > 1) d),
                        orphans AS (
                            SELECT count(*) AS seats FROM seats st
                            WHERE st.show_id = :show AND st.status <> 'available'
                              AND NOT EXISTS (
                                  SELECT 1 FROM active a
                                  WHERE a.id = st.reservation_id AND a.user_id = st.user_id
                                    AND st.seat_label = ANY(a.seats)))
                        SELECT sh.total_seats, ss.available, ss.held, ss.confirmed,
                               r.seats AS reserved_seats, c.total AS counted_seats,
                               c.over_limit, c.max_per_user, sh.per_user_limit,
                               d.seats AS duplicate_seats, o.seats AS orphan_seats
                        FROM shows sh, seat_states ss, reserved r, counters c, duplicates d, orphans o
                        WHERE sh.id = :show
                        """)
                .param("show", showId)
                .query((rs, row) -> new ReconcileNumbers(
                        rs.getInt("total_seats"),
                        rs.getInt("available"),
                        rs.getInt("held"),
                        rs.getInt("confirmed"),
                        rs.getInt("reserved_seats"),
                        rs.getInt("counted_seats"),
                        rs.getInt("over_limit"),
                        rs.getInt("max_per_user"),
                        rs.getInt("per_user_limit"),
                        rs.getInt("duplicate_seats"),
                        rs.getInt("orphan_seats")))
                .optional();
    }
}
