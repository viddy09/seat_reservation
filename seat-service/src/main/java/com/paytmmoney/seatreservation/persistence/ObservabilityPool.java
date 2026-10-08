package com.paytmmoney.seatreservation.persistence;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * A second, tiny connection pool reserved for readiness checks and scrape-time gauges (ADR-015).
 *
 * <p>During a burst the main pool is saturated by design: requests queue for a connection for up to ~20s. If readiness
 * borrowed from that pool it would queue too, time out, get the instance marked unhealthy and restarted, and the
 * restart would produce exactly the 5xx we must avoid. This pool is deliberately not exposed as a {@code DataSource}
 * bean so it can never be picked up by accident (and so Spring Boot still auto-configures the main pool).
 */
public final class ObservabilityPool implements AutoCloseable {

    private final HikariDataSource dataSource;
    private final JdbcClient jdbcClient;

    public ObservabilityPool(HikariDataSource dataSource) {
        this.dataSource = dataSource;
        this.jdbcClient = JdbcClient.create(dataSource);
    }

    JdbcClient jdbcClient() {
        return jdbcClient;
    }

    public HikariPoolMXBean poolMxBean() {
        return dataSource.getHikariPoolMXBean();
    }

    @Override
    public void close() {
        dataSource.close();
    }
}
