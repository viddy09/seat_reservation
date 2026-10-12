package com.paytmmoney.seatreservation.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Reservation outcome counters. Every reason is registered up front at 0, so a dashboard or the burst tool can diff
 * values from the very first request.
 */
@Component
public class ReservationMetrics {

    private final Counter confirmed;
    private final Counter cancelled;
    private final Map<String, Counter> declined;

    public ReservationMetrics(MeterRegistry registry) {
        this.confirmed = Counter.builder(MetricNames.RESERVATIONS_CONFIRMED)
                .description("Reservations created (201)")
                .register(registry);
        this.cancelled = Counter.builder(MetricNames.RESERVATIONS_CANCELLED)
                .description("Reservations cancelled (state-changing cancels only)")
                .register(registry);
        this.declined = MetricNames.DECLINE_REASONS.stream()
                .collect(Collectors.toUnmodifiableMap(
                        Function.identity(),
                        reason -> Counter.builder(MetricNames.RESERVATIONS_DECLINED)
                                .description("Reserve requests that did not create a reservation, by reason")
                                .tag(MetricNames.TAG_REASON, reason)
                                .register(registry)));
    }

    void confirmed() {
        confirmed.increment();
    }

    void cancelled() {
        cancelled.increment();
    }

    void declined(String reason) {
        Counter counter = declined.get(reason);
        if (counter == null) {
            throw new IllegalArgumentException("Unknown decline reason: " + reason);
        }
        counter.increment();
    }
}
