package com.paytmmoney.seatreservation.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Runs just inside the request-id filter and outside Spring Security, so it sees every request, including 401s.
 * Once the response is complete it records, from the same facts: {@code http_requests_total},
 * {@code http_request_duration_seconds}, the reservation outcome counters, and one JSON access-log line.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class HttpObservabilityFilter extends OncePerRequestFilter {

    /** Histogram buckets: fine-grained where the SLO lives, coarse up to the pool-acquire timeout. */
    private static final Duration[] LATENCY_BUCKETS = {
        Duration.ofMillis(5),
        Duration.ofMillis(10),
        Duration.ofMillis(25),
        Duration.ofMillis(50),
        Duration.ofMillis(100),
        Duration.ofMillis(250),
        Duration.ofMillis(500),
        Duration.ofSeconds(1),
        Duration.ofMillis(2500),
        Duration.ofSeconds(5),
        Duration.ofSeconds(10),
        Duration.ofSeconds(20)
    };

    private static final Logger access = LoggerFactory.getLogger("access");
    private static final String RESERVATION_ID_KEY = "reservation_id";

    private final MeterRegistry registry;
    private final ReservationMetrics reservations;

    public HttpObservabilityFilter(MeterRegistry registry, ReservationMetrics reservations) {
        this.registry = registry;
        this.reservations = reservations;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long start = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            Duration latency = Duration.ofNanos(System.nanoTime() - start);
            String route = Routes.resolve(request.getRequestURI());
            int status = response.getStatus();
            Optional<String> outcome = RequestOutcomes.outcome(request);
            Optional<String> declineReason = recordReservationOutcome(request, route, status, outcome);
            recordHttp(request.getMethod(), route, status, latency);
            logAccess(request, route, status, latency, outcome, declineReason);
        }
    }

    private Optional<String> recordReservationOutcome(
            HttpServletRequest request, String route, int status, Optional<String> outcome) {
        if (!HttpMethod.POST.matches(request.getMethod())) {
            return Optional.empty();
        }
        if (Routes.CANCEL.equals(route)) {
            if (outcome.filter(RequestOutcomes.CANCELLED::equals).isPresent()) {
                reservations.cancelled();
            }
            return Optional.empty();
        }
        if (!Routes.RESERVE.equals(route) || status >= HttpStatus.INTERNAL_SERVER_ERROR.value()) {
            // A 5xx is an error, not a decline: it is visible in http_requests_total and pages on its own.
            return Optional.empty();
        }
        if (status == HttpStatus.CREATED.value()) {
            reservations.confirmed();
            return Optional.empty();
        }
        String reason = outcome.filter(RequestOutcomes.IDEMPOTENT_REPLAY::equals)
                .orElseGet(() -> DeclineReasons.forErrorCode(outcome.orElse(null), status));
        reservations.declined(reason);
        return Optional.of(reason);
    }

    private void recordHttp(String method, String route, int status, Duration latency) {
        Counter.builder(MetricNames.HTTP_REQUESTS)
                .description("HTTP requests by route template, method and status code")
                .tag(MetricNames.TAG_ROUTE, route)
                .tag(MetricNames.TAG_METHOD, method)
                .tag(MetricNames.TAG_CODE, String.valueOf(status))
                .register(registry)
                .increment();
        Timer.builder(MetricNames.HTTP_REQUEST_DURATION)
                .description("HTTP request latency by route template")
                .tag(MetricNames.TAG_ROUTE, route)
                .serviceLevelObjectives(LATENCY_BUCKETS)
                .register(registry)
                .record(latency);
    }

    /** One line per request. request_id, user_id and show_id arrive via MDC; tokens are never logged. */
    private static void logAccess(
            HttpServletRequest request,
            String route,
            int status,
            Duration latency,
            Optional<String> outcome,
            Optional<String> declineReason) {
        LoggingEventBuilder event = Routes.isOperational(route) ? access.atDebug() : access.atInfo();
        event = event.addKeyValue("method", request.getMethod())
                .addKeyValue("route", route)
                .addKeyValue("status", status)
                .addKeyValue("latency_ms", latency.toMillis())
                .addKeyValue("outcome", outcome.orElse(status < HttpStatus.BAD_REQUEST.value() ? "ok" : "error"));
        if (declineReason.isPresent()) {
            event = event.addKeyValue("decline_reason", declineReason.get());
        }
        // On /reservations/{id}/* the id is already in the MDC; writing it twice makes the JSON encoder drop the line.
        Optional<String> reservationId = RequestOutcomes.reservationId(request);
        if (reservationId.isPresent() && MDC.get(RESERVATION_ID_KEY) == null) {
            event = event.addKeyValue(RESERVATION_ID_KEY, reservationId.get());
        }
        event.log("{} {} {} {}ms", request.getMethod(), route, status, latency.toMillis());
    }
}
