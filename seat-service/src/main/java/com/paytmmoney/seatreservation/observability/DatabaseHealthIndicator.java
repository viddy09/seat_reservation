package com.paytmmoney.seatreservation.observability;

import com.paytmmoney.seatreservation.persistence.DatabaseProbe;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Database check for {@code /readyz}. Uses the observability pool via {@link DatabaseProbe}, never the main pool, so it
 * answers in about a second even while the burst holds every main-pool connection (ADR-015). Liveness deliberately
 * does not include this check: a database outage must stop traffic, not restart a healthy process.
 */
@Component("database")
public class DatabaseHealthIndicator implements HealthIndicator {

    private final DatabaseProbe probe;

    public DatabaseHealthIndicator(DatabaseProbe probe) {
        this.probe = probe;
    }

    @Override
    public Health health() {
        return probe.isReachable() ? Health.up().build() : Health.down().build();
    }
}
