package com.paytmmoney.seatreservation.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Typed, validated application settings. Values come from environment variables via application.yml; an invalid
 * value fails startup with a message naming the property, rather than surfacing later as odd runtime behaviour.
 */
@Validated
@ConfigurationProperties(prefix = "seatres")
public record AppProperties(
        @Valid @NotNull Db db, @Valid @NotNull Observability observability) {

    /**
     * Main request-path pool. Railway Postgres allows 100 connections; the pool must leave room for the
     * observability pool, Flyway, psql and a redeploy overlap where two instances are briefly connected (ADR-024).
     */
    public record Db(@Min(2) @Max(80) int maxConns) {}

    /**
     * The tiny, separate pool used by readiness and scrape-time gauges, so they never queue behind a saturated main
     * pool during a burst (ADR-015). Timeouts are deliberately short: a probe that cannot answer within about a second
     * should report DOWN, not hang.
     */
    public record Observability(
            @Min(1) @Max(4) int poolSize,

            @NotNull @DurationMin(millis = 250) @DurationMax(seconds = 5)
            Duration connectionTimeout,

            @NotNull @DurationMin(millis = 100) @DurationMax(seconds = 5)
            Duration statementTimeout) {}
}
