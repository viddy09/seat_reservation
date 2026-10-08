package com.paytmmoney.seatreservation.config;

import com.paytmmoney.seatreservation.persistence.ObservabilityPool;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Builds the observability pool from the same connection details as the main pool (ADR-015). */
@Configuration(proxyBeanMethods = false)
public class ObservabilityPoolConfig {

    static final String POOL_NAME = "obs";

    /** Validation must finish well inside the connection timeout; Hikari's floor for both is 250ms. */
    private static final long VALIDATION_TIMEOUT_DIVISOR = 2;

    private static final long HIKARI_MIN_TIMEOUT_MILLIS = 250;

    @Bean(destroyMethod = "close")
    ObservabilityPool observabilityPool(JdbcConnectionDetails connection, AppProperties properties) {
        AppProperties.Observability settings = properties.observability();

        HikariConfig config = new HikariConfig();
        config.setPoolName(POOL_NAME);
        config.setJdbcUrl(connection.getJdbcUrl());
        config.setUsername(connection.getUsername());
        config.setPassword(connection.getPassword());
        config.setDriverClassName(connection.getDriverClassName());
        config.setMaximumPoolSize(settings.poolSize());
        config.setMinimumIdle(1);
        config.setConnectionTimeout(settings.connectionTimeout().toMillis());
        config.setValidationTimeout(Math.max(
                HIKARI_MIN_TIMEOUT_MILLIS, settings.connectionTimeout().toMillis() / VALIDATION_TIMEOUT_DIVISOR));
        // Start even if the database is down: readiness reports it instead of the app failing to boot.
        config.setInitializationFailTimeout(-1);
        config.addDataSourceProperty(
                "options", "-c statement_timeout=" + settings.statementTimeout().toMillis());
        config.addDataSourceProperty("ApplicationName", "seat-service-obs");
        return new ObservabilityPool(new HikariDataSource(config));
    }
}
