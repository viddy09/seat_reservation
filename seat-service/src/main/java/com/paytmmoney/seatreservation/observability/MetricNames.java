package com.paytmmoney.seatreservation.observability;

import java.util.List;

/**
 * Meter names (Micrometer dotted form; Prometheus renders {@code a.b.c} counters as {@code a_b_c_total}). Kept in one
 * place so dashboards, alerts and docs/OBSERVABILITY.md have a single source of truth.
 */
public final class MetricNames {

    public static final String RESERVATIONS_CONFIRMED = "seatres.reservations.confirmed";
    public static final String RESERVATIONS_DECLINED = "seatres.reservations.declined";
    public static final String RESERVATIONS_CANCELLED = "seatres.reservations.cancelled";
    public static final String SEATS = "seatres_seats";
    public static final String SEATS_AVAILABLE = "seatres_seats_available";
    public static final String HTTP_REQUESTS = "http.requests";
    public static final String HTTP_REQUEST_DURATION = "http.request.duration";

    public static final String TAG_REASON = "reason";
    public static final String TAG_ROUTE = "route";
    public static final String TAG_METHOD = "method";
    public static final String TAG_CODE = "code";

    /** Every decline reason, pre-registered at 0 so rate() and burst deltas work from the first request. */
    public static final List<String> DECLINE_REASONS = List.of(
            "seat_taken",
            "per_user_limit",
            "idempotent_replay",
            "idempotency_key_reused",
            "validation",
            "unauthorized",
            "not_found",
            "contention",
            "server_busy");

    private MetricNames() {}
}
