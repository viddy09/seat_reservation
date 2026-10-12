package com.paytmmoney.seatreservation.observability;

import com.paytmmoney.seatreservation.domain.SeatStatus;
import com.paytmmoney.seatreservation.persistence.SeatCountsRepository;
import com.paytmmoney.seatreservation.persistence.SeatCountsRepository.ShowSeatCount;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.prometheus.metrics.model.registry.MultiCollector;
import io.prometheus.metrics.model.snapshots.GaugeSnapshot;
import io.prometheus.metrics.model.snapshots.GaugeSnapshot.GaugeDataPointSnapshot;
import io.prometheus.metrics.model.snapshots.Labels;
import io.prometheus.metrics.model.snapshots.MetricSnapshots;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * {@code seatres_seats{show_id,status}} and {@code seatres_seats_available{show_id}}, computed from the database at
 * scrape time, so the gauges always reconcile with what the API reports (they are the source of truth; in-process
 * counters are not).
 *
 * <p>Registered as a Prometheus collector rather than Micrometer gauges so the query runs exactly when Prometheus
 * scrapes. The result is cached for at most {@link #MAX_AGE} so concurrent scrapes don't each hit the database.
 */
@Component
public final class SeatGaugeCollector implements MultiCollector {

    static final Duration MAX_AGE = Duration.ofSeconds(1);
    static final int MAX_SHOWS = 50;

    private static final Logger log = LoggerFactory.getLogger(SeatGaugeCollector.class);

    private final SeatCountsRepository seatCounts;
    private final Clock clock;
    // ReentrantLock, not synchronized: a virtual thread blocked on JDBC inside synchronized pins its carrier on Java
    // 21.
    private final ReentrantLock refresh = new ReentrantLock();
    private volatile Cached cached = new Cached(Instant.EPOCH, List.of());

    public SeatGaugeCollector(SeatCountsRepository seatCounts, PrometheusMeterRegistry registry) {
        this.seatCounts = seatCounts;
        this.clock = Clock.systemUTC();
        registry.getPrometheusRegistry().register(this);
    }

    @Override
    public MetricSnapshots collect() {
        Map<UUID, Map<SeatStatus, Long>> byShow = group(current());

        GaugeSnapshot.Builder seats = GaugeSnapshot.builder()
                .name(MetricNames.SEATS)
                .help("Seats per show and status, read from the database at scrape time");
        GaugeSnapshot.Builder available = GaugeSnapshot.builder()
                .name(MetricNames.SEATS_AVAILABLE)
                .help("Available seats per show, read from the database at scrape time");
        byShow.forEach((showId, counts) -> {
            for (SeatStatus status : SeatStatus.values()) {
                seats.dataPoint(GaugeDataPointSnapshot.builder()
                        .labels(Labels.of("show_id", showId.toString(), "status", status.dbValue()))
                        .value(counts.getOrDefault(status, 0L))
                        .build());
            }
            available.dataPoint(GaugeDataPointSnapshot.builder()
                    .labels(Labels.of("show_id", showId.toString()))
                    .value(counts.getOrDefault(SeatStatus.AVAILABLE, 0L))
                    .build());
        });
        return MetricSnapshots.of(seats.build(), available.build());
    }

    private List<ShowSeatCount> current() {
        Cached snapshot = cached;
        if (isFresh(snapshot)) {
            return snapshot.rows();
        }
        refresh.lock();
        try {
            snapshot = cached;
            if (isFresh(snapshot)) {
                return snapshot.rows();
            }
            List<ShowSeatCount> rows = seatCounts.recentShows(MAX_SHOWS);
            cached = new Cached(clock.instant(), rows);
            return rows;
        } catch (DataAccessException e) {
            // Emit no seat samples rather than stale ones; readiness reports the database outage separately.
            log.warn("Seat gauges unavailable: {}", e.getMessage());
            return List.of();
        } finally {
            refresh.unlock();
        }
    }

    private boolean isFresh(Cached snapshot) {
        return Duration.between(snapshot.at(), clock.instant()).compareTo(MAX_AGE) < 0;
    }

    private static Map<UUID, Map<SeatStatus, Long>> group(List<ShowSeatCount> rows) {
        Map<UUID, Map<SeatStatus, Long>> byShow = new LinkedHashMap<>();
        for (ShowSeatCount row : rows) {
            byShow.computeIfAbsent(row.showId(), id -> new EnumMap<>(SeatStatus.class))
                    .put(row.status(), row.seats());
        }
        return byShow;
    }

    private record Cached(Instant at, List<ShowSeatCount> rows) {}
}
