package com.paytmmoney.seatreservation.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Readiness must fail closed and fast when the database is unreachable, never hang. */
class DatabaseProbeTest {

    private static final Duration UPPER_BOUND = Duration.ofSeconds(3);

    @Test
    void unreachableDatabase_reportsFalseQuickly() {
        HikariConfig config = new HikariConfig();
        // Port 1 on localhost: connection refused immediately.
        config.setJdbcUrl("jdbc:postgresql://127.0.0.1:1/none");
        config.setUsername("x");
        config.setPassword("x");
        config.setMaximumPoolSize(1);
        config.setConnectionTimeout(1000);
        config.setInitializationFailTimeout(-1);

        try (ObservabilityPool pool = new ObservabilityPool(new HikariDataSource(config))) {
            DatabaseProbe probe = new DatabaseProbe(pool);

            long start = System.nanoTime();
            boolean reachable = probe.isReachable();
            Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

            assertThat(reachable).isFalse();
            assertThat(elapsed).isLessThan(UPPER_BOUND);
        }
    }
}
