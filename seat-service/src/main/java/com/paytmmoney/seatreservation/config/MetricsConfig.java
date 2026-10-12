package com.paytmmoney.seatreservation.config;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.config.MeterFilter;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class MetricsConfig {

    /**
     * HikariCP's own Micrometer meters, exported under the {@code db_pool_*} names the runbook uses, for both pools
     * (tag {@code pool=main|obs}). Renaming keeps a single source of truth instead of a second set of gauges:
     * <ul>
     *   <li>{@code db_pool_acquired} / {@code db_pool_idle} / {@code db_pool_connections} (total) / {@code db_pool_max}
     *   <li>{@code db_pool_pending}: threads waiting for a connection right now
     *   <li>{@code db_pool_wait_seconds_count|sum|max}: wait count and total wait duration for a connection
     *   <li>{@code db_pool_timeouts_total}: acquire attempts that timed out (each one is a 429)
     * </ul>
     */
    @Bean
    MeterFilter dbPoolNames() {
        Map<String, String> renames = Map.of(
                "hikaricp.connections.active", "db.pool.acquired",
                "hikaricp.connections.idle", "db.pool.idle",
                "hikaricp.connections", "db.pool.connections",
                "hikaricp.connections.max", "db.pool.max",
                "hikaricp.connections.pending", "db.pool.pending",
                "hikaricp.connections.acquire", "db.pool.wait",
                "hikaricp.connections.timeout", "db.pool.timeouts");
        return new MeterFilter() {
            @Override
            public Meter.Id map(Meter.Id id) {
                String renamed = renames.get(id.getName());
                return renamed == null ? id : id.withName(renamed);
            }
        };
    }
}
