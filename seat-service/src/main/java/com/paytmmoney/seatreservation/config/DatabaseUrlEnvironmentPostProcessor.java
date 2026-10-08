package com.paytmmoney.seatreservation.config;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.util.StringUtils;

/**
 * Accepts the platform-style {@code DATABASE_URL} ({@code postgres://user:pass@host:5432/db?sslmode=require}) and
 * translates it to Spring's JDBC properties, so deployments need no hand-edited JDBC URL. An explicit
 * {@code SPRING_DATASOURCE_URL} always wins.
 */
public final class DatabaseUrlEnvironmentPostProcessor implements EnvironmentPostProcessor {

    static final String DATABASE_URL = "DATABASE_URL";
    /** Checked by its raw name: application.yml always defines spring.datasource.url with a local default. */
    static final String EXPLICIT_JDBC_URL = "SPRING_DATASOURCE_URL";

    private static final String PROPERTY_SOURCE_NAME = "databaseUrl";
    private static final String DATASOURCE_URL = "spring.datasource.url";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String databaseUrl = environment.getProperty(DATABASE_URL);
        if (!StringUtils.hasText(databaseUrl) || StringUtils.hasText(environment.getProperty(EXPLICIT_JDBC_URL))) {
            return;
        }
        environment.getPropertySources().addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, toJdbc(databaseUrl)));
    }

    static Map<String, Object> toJdbc(String databaseUrl) {
        URI uri = URI.create(databaseUrl.trim());
        String scheme = uri.getScheme();
        if (!"postgres".equals(scheme) && !"postgresql".equals(scheme)) {
            throw new IllegalArgumentException(
                    "DATABASE_URL must start with postgres:// or postgresql://, got scheme: " + scheme);
        }
        if (!StringUtils.hasText(uri.getHost())) {
            throw new IllegalArgumentException("DATABASE_URL has no host");
        }

        StringBuilder jdbcUrl = new StringBuilder("jdbc:postgresql://").append(uri.getHost());
        if (uri.getPort() != -1) {
            jdbcUrl.append(':').append(uri.getPort());
        }
        jdbcUrl.append(StringUtils.hasText(uri.getRawPath()) ? uri.getRawPath() : "/postgres");
        if (StringUtils.hasText(uri.getRawQuery())) {
            jdbcUrl.append('?').append(uri.getRawQuery());
        }

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(DATASOURCE_URL, jdbcUrl.toString());
        String userInfo = uri.getRawUserInfo();
        if (StringUtils.hasText(userInfo)) {
            int colon = userInfo.indexOf(':');
            String user = colon >= 0 ? userInfo.substring(0, colon) : userInfo;
            properties.put("spring.datasource.username", decode(user));
            if (colon >= 0) {
                properties.put("spring.datasource.password", decode(userInfo.substring(colon + 1)));
            }
        }
        return properties;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
