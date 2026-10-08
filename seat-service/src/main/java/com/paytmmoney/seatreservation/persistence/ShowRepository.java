package com.paytmmoney.seatreservation.persistence;

import com.paytmmoney.seatreservation.domain.NewShow;
import com.paytmmoney.seatreservation.domain.Seat;
import com.paytmmoney.seatreservation.domain.SeatStatus;
import com.paytmmoney.seatreservation.domain.Show;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ShowRepository {

    private final JdbcClient jdbc;

    public ShowRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserts the show and all its seats; the caller runs this inside one transaction. */
    public Show insert(UUID id, NewShow show) {
        Instant createdAt = jdbc.sql("""
                        INSERT INTO shows (id, name, price_paise, per_user_limit, total_seats)
                        VALUES (?, ?, ?, ?, ?)
                        RETURNING created_at
                        """)
                .params(
                        id,
                        show.name(),
                        show.pricePaise(),
                        show.perUserLimit(),
                        show.seats().size())
                .query(OffsetDateTime.class)
                .single()
                .toInstant();
        // One statement, one round trip, however many seats.
        jdbc.sql("INSERT INTO seats (show_id, seat_label) SELECT ?, unnest(?::text[])")
                .params(id, show.seats().toArray(String[]::new))
                .update();
        return new Show(
                id,
                show.name(),
                show.pricePaise(),
                show.perUserLimit(),
                show.seats().size(),
                createdAt);
    }

    public Optional<Show> findById(UUID id) {
        return jdbc.sql("""
                        SELECT id, name, price_paise, per_user_limit, total_seats, created_at
                        FROM shows WHERE id = ?
                        """)
                .param(id)
                .query((rs, row) -> new Show(
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        rs.getLong("price_paise"),
                        rs.getInt("per_user_limit"),
                        rs.getInt("total_seats"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()))
                .optional();
    }

    /** All seats of a show in one statement, so the derived counts come from a single consistent snapshot. */
    public List<Seat> findSeats(UUID showId) {
        return jdbc.sql("SELECT seat_label, status FROM seats WHERE show_id = ? ORDER BY seat_label")
                .param(showId)
                .query((rs, row) -> new Seat(rs.getString("seat_label"), SeatStatus.fromDb(rs.getString("status"))))
                .list();
    }

    /**
     * Labels from {@code labels} that do not exist in the show. Safe outside the reserve transaction because a show's
     * seat set never changes after creation (ADR-026).
     */
    public List<String> findMissingSeats(UUID showId, List<String> labels) {
        return jdbc.sql("""
                        SELECT requested.label
                        FROM unnest(?::text[]) AS requested(label)
                        WHERE NOT EXISTS (
                            SELECT 1 FROM seats s WHERE s.show_id = ? AND s.seat_label = requested.label)
                        """).params(labels.toArray(String[]::new), showId).query(String.class).list().stream()
                .sorted()
                .toList();
    }
}
