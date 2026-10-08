package com.paytmmoney.seatreservation.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

class DatabaseUrlEnvironmentPostProcessorTest {

    private final DatabaseUrlEnvironmentPostProcessor processor = new DatabaseUrlEnvironmentPostProcessor();

    @Test
    void convertsPlatformUrlToJdbcProperties() {
        Map<String, Object> props = DatabaseUrlEnvironmentPostProcessor.toJdbc(
                "postgresql://postgres:s%40cr%3At@postgres.railway.internal:5432/railway?sslmode=disable");

        assertThat(props)
                .containsEntry(
                        "spring.datasource.url",
                        "jdbc:postgresql://postgres.railway.internal:5432/railway?sslmode=disable")
                .containsEntry("spring.datasource.username", "postgres")
                .containsEntry("spring.datasource.password", "s@cr:t");
    }

    @Test
    void acceptsPostgresSchemeWithoutPortOrQuery() {
        assertThat(DatabaseUrlEnvironmentPostProcessor.toJdbc("postgres://u:p@db/app"))
                .containsEntry("spring.datasource.url", "jdbc:postgresql://db/app");
    }

    @Test
    void rejectsOtherSchemes() {
        assertThatThrownBy(() -> DatabaseUrlEnvironmentPostProcessor.toJdbc("mysql://u:p@db/app"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("postgres://");
    }

    @Test
    void appliesDatabaseUrlOverYamlDefaults() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("spring.datasource.url", "jdbc:postgresql://localhost:5432/seatres")
                .withProperty(DatabaseUrlEnvironmentPostProcessor.DATABASE_URL, "postgres://u:p@db:6543/app");

        processor.postProcessEnvironment(env, new SpringApplication());

        assertThat(env.getProperty("spring.datasource.url")).isEqualTo("jdbc:postgresql://db:6543/app");
        assertThat(env.getProperty("spring.datasource.username")).isEqualTo("u");
    }

    @Test
    void explicitJdbcUrlWins() {
        MockEnvironment env = new MockEnvironment()
                .withProperty(DatabaseUrlEnvironmentPostProcessor.EXPLICIT_JDBC_URL, "jdbc:postgresql://explicit/db")
                .withProperty("spring.datasource.url", "jdbc:postgresql://explicit/db")
                .withProperty(DatabaseUrlEnvironmentPostProcessor.DATABASE_URL, "postgres://u:p@db/app");

        processor.postProcessEnvironment(env, new SpringApplication());

        assertThat(env.getProperty("spring.datasource.url")).isEqualTo("jdbc:postgresql://explicit/db");
    }
}
