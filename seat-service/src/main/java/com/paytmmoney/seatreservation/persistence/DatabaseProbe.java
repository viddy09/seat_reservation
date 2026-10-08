package com.paytmmoney.seatreservation.persistence;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Repository;

/** Answers "can we reach Postgres right now?" using only the observability pool. */
@Repository
public class DatabaseProbe {

    private final ObservabilityPool pool;

    public DatabaseProbe(ObservabilityPool pool) {
        this.pool = pool;
    }

    /**
     * Runs a trivial query. Bounded by the pool's ~1s connection timeout and its session {@code statement_timeout}, so
     * a dead or overloaded database yields {@code false} quickly instead of hanging the caller.
     */
    public boolean isReachable() {
        try {
            Integer one = pool.jdbcClient().sql("SELECT 1").query(Integer.class).single();
            return one != null && one == 1;
        } catch (DataAccessException e) {
            return false;
        }
    }
}
