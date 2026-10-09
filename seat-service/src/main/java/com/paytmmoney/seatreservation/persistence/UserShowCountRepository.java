package com.paytmmoney.seatreservation.persistence;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class UserShowCountRepository {

    private final JdbcClient jdbc;

    public UserShowCountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Lock-order step 2: add {@code seats} to the user's count for the show, only if the result stays within
     * {@code limit}. One atomic conditional upsert, never read-then-write (ADR-012): concurrent requests from the same
     * user serialise on this row, and each sees the count the previous one committed.
     *
     * <p>Returns false (no row) when the limit would be exceeded. The plain-insert branch is always within the limit
     * because the caller already rejected {@code seats > limit}.
     */
    public boolean tryAdd(UUID showId, String userId, int seats, int limit) {
        return jdbc.sql("""
                        INSERT INTO user_show_counts (show_id, user_id, seat_count) VALUES (?, ?, ?)
                        ON CONFLICT (show_id, user_id) DO UPDATE
                            SET seat_count = user_show_counts.seat_count + EXCLUDED.seat_count
                            WHERE user_show_counts.seat_count + EXCLUDED.seat_count <= ?
                        RETURNING seat_count
                        """)
                .params(showId, userId, seats, limit)
                .query(Integer.class)
                .optional()
                .isPresent();
    }

    /**
     * Cancel lock-order step 2: give the seats back to the user's quota. Returns rows updated (1 when consistent); the
     * {@code seat_count >= 0} CHECK makes drift fail loudly rather than go negative.
     */
    public int subtract(UUID showId, String userId, int seats) {
        return jdbc.sql("UPDATE user_show_counts SET seat_count = seat_count - ? WHERE show_id = ? AND user_id = ?")
                .params(seats, showId, userId)
                .update();
    }
}
