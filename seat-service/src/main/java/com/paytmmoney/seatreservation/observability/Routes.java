package com.paytmmoney.seatreservation.observability;

import java.util.List;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * Maps a raw path to its route template for metric labels and logs. Resolved here rather than from Spring MVC's
 * matched pattern because requests rejected by Spring Security (401/403) never reach MVC, and those must still be
 * labelled correctly. Unknown paths collapse to {@code unmatched}, keeping label cardinality bounded.
 */
final class Routes {

    static final String RESERVE = "/shows/{showId}/reserve";
    static final String CANCEL = "/reservations/{reservationId}/cancel";
    static final String UNMATCHED = "unmatched";

    private static final List<String> TEMPLATES = List.of(
            "/auth/token",
            "/shows",
            "/shows/{showId}",
            RESERVE,
            "/shows/{showId}/reconcile",
            "/reservations/{reservationId}",
            CANCEL,
            "/healthz",
            "/readyz",
            "/metrics",
            "/actuator/**");

    /** Probe and scrape traffic: logged at DEBUG so a 10s health check doesn't drown the access log. */
    private static final List<String> OPERATIONAL = List.of("/healthz", "/readyz", "/metrics", "/actuator/**");

    private static final List<Route> ROUTES = TEMPLATES.stream()
            .map(template -> new Route(template, PathPatternParser.defaultInstance.parse(template)))
            .toList();

    private Routes() {}

    static String resolve(String path) {
        PathContainer container = PathContainer.parsePath(path);
        return ROUTES.stream()
                .filter(route -> route.pattern().matches(container))
                .map(Route::template)
                .findFirst()
                .orElse(UNMATCHED);
    }

    static boolean isOperational(String route) {
        return OPERATIONAL.contains(route);
    }

    private record Route(String template, PathPattern pattern) {}
}
