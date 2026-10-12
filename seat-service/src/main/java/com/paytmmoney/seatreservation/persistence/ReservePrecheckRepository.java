package com.paytmmoney.seatreservation.persistence;

import com.paytmmoney.seatreservation.domain.Show;
import java.sql.Array;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Everything reserve needs to know before its transaction, in ONE non-locking read (ADR-040): the show, which
 * requested seats don't exist, which are already taken, and whether this (user, key) already has a reservation.
 *
 * <p>The "already taken" part is the hot-path optimisation: under a stampede most requests are for seats someone
 * already holds, and declining them here costs one short read instead of a transaction that queues on a row lock
 * while holding a pool connection. It is safe because the transaction still makes the real decision; a stale read can
 * only produce a decline, which is a legitimate race outcome, never a double-sell.
 */
@Repository
public class ReservePrecheckRepository {

    private final JdbcClient jdbc;

    public ReservePrecheckRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Precheck(Show show, List<String> missingSeats, List<String> unavailableSeats, boolean keyExists) {

        public Precheck {
            missingSeats = List.copyOf(missingSeats);
            unavailableSeats = List.copyOf(unavailableSeats);
        }
    }

    public Optional<Precheck> find(UUID showId, String userId, String idempotencyKey, List<String> seats) {
        String[] labels = seats.toArray(String[]::new);
        return jdbc.sql("""
                        SELECT s.id, s.name, s.price_paise, s.per_user_limit, s.total_seats, s.created_at,
                               (SELECT coalesce(array_agg(requested.label ORDER BY requested.label), '{}')
                                  FROM unnest(:seats::text[]) AS requested(label)
                                 WHERE NOT EXISTS (SELECT 1 FROM seats x
                                                    WHERE x.show_id = s.id AND x.seat_label = requested.label))
                                   AS missing,
                               (SELECT coalesce(array_agg(x.seat_label ORDER BY x.seat_label), '{}')
                                  FROM seats x
                                 WHERE x.show_id = s.id AND x.seat_label = ANY(:seats::text[])
                                   AND x.status <> 'available')
                                   AS unavailable,
                               EXISTS (SELECT 1 FROM reservations r
                                        WHERE r.user_id = :user AND r.idempotency_key = :key)
                                   AS key_exists
                        FROM shows s
                        WHERE s.id = :show
                        """)
                .param("seats", labels)
                .param("user", userId)
                .param("key", idempotencyKey)
                .param("show", showId)
                .query((rs, row) -> new Precheck(
                        new Show(
                                rs.getObject("id", UUID.class),
                                rs.getString("name"),
                                rs.getLong("price_paise"),
                                rs.getInt("per_user_limit"),
                                rs.getInt("total_seats"),
                                rs.getObject("created_at", OffsetDateTime.class).toInstant()),
                        textArray(rs.getArray("missing")),
                        textArray(rs.getArray("unavailable")),
                        rs.getBoolean("key_exists")))
                .optional();
    }

    private static List<String> textArray(Array array) throws SQLException {
        try {
            return List.of((String[]) array.getArray());
        } finally {
            array.free();
        }
    }
}
