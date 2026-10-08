package com.paytmmoney.seatreservation.persistence;

import com.paytmmoney.seatreservation.domain.Seat;
import com.paytmmoney.seatreservation.domain.SeatStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class SeatRepository {

    private final JdbcClient jdbc;

    public SeatRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Lock-order step 3: row-lock the requested seats in {@code seat_label} order (COLLATE "C"), the same order in
     * every transaction, so two requests for overlapping seats can never wait on each other in a cycle (ADR-006).
     *
     * <p>Plain {@code FOR UPDATE}, deliberately without NOWAIT/SKIP LOCKED: competitors queue on the lock and then see
     * the committed status. If the holder rolls back, the next in line still wins, so a hot seat can never end with
     * zero winners (ADR-007).
     */
    public List<Seat> lockForUpdate(UUID showId, List<String> labels) {
        return jdbc.sql("""
                        SELECT seat_label, status FROM seats
                        WHERE show_id = ? AND seat_label = ANY(?::text[])
                        ORDER BY seat_label
                        FOR UPDATE
                        """)
                .params(showId, labels.toArray(String[]::new))
                .query((rs, row) -> new Seat(rs.getString("seat_label"), SeatStatus.fromDb(rs.getString("status"))))
                .list();
    }

    /**
     * Confirms seats already locked by {@link #lockForUpdate}. The {@code status = 'available'} guard is defence in
     * depth: the caller compares the returned row count with the number of seats requested.
     */
    public int confirm(UUID showId, List<String> labels, UUID reservationId, String userId) {
        return jdbc.sql("""
                        UPDATE seats
                        SET status = 'confirmed', reservation_id = ?, user_id = ?, updated_at = now()
                        WHERE show_id = ? AND seat_label = ANY(?::text[]) AND status = 'available'
                        """)
                .params(reservationId, userId, showId, labels.toArray(String[]::new))
                .update();
    }
}
