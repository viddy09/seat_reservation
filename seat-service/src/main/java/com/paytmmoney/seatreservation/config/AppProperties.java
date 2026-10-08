package com.paytmmoney.seatreservation.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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
        @Valid @NotNull Db db,
        @Valid @NotNull Observability observability,
        @Valid @NotNull Auth auth) {

    /**
     * Token settings. {@code JWT_SECRET} is an HS256 key and must be at least 32 bytes (RFC 7518 §3.2): a shorter key
     * fails startup instead of silently weakening every token. {@code ADMIN_API_KEY} may be empty, in which case no
     * admin token can be minted.
     */
    public record Auth(
            @NotBlank @Size(min = 32, message = "JWT_SECRET must be at least 32 characters")
            String jwtSecret,

            @NotNull String adminApiKey,
            boolean allowDevTokens,

            @NotNull @DurationMin(minutes = 1) @DurationMax(hours = 24)
            Duration tokenTtl) {}

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
