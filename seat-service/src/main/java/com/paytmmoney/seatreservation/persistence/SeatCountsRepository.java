package com.paytmmoney.seatreservation.persistence;

import com.paytmmoney.seatreservation.domain.SeatStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * Seat counts per show and status for the scrape-time gauges. Runs on the observability pool, never the main pool, so a
 * scrape during a burst doesn't queue behind reservations (ADR-015). One statement, one consistent snapshot.
 */
@Repository
public class SeatCountsRepository {

    private final ObservabilityPool pool;

    public SeatCountsRepository(ObservabilityPool pool) {
        this.pool = pool;
    }

    /** Counts for the {@code limit} most recently created shows (bounded label cardinality, ADR-025). */
    public List<ShowSeatCount> recentShows(int limit) {
        return pool.jdbcClient()
                .sql("""
                        SELECT recent.id, st.status, count(*) AS seats
                        FROM (SELECT id FROM shows ORDER BY created_at DESC LIMIT ?) recent
                        JOIN seats st ON st.show_id = recent.id
                        GROUP BY recent.id, st.status
                        """)
                .param(limit)
                .query((rs, row) -> new ShowSeatCount(
                        rs.getObject("id", UUID.class), SeatStatus.fromDb(rs.getString("status")), rs.getLong("seats")))
                .list();
    }

    public record ShowSeatCount(UUID showId, SeatStatus status, long seats) {}
}
