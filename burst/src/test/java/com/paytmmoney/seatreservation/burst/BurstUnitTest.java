package com.paytmmoney.seatreservation.burst;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class BurstUnitTest {

    @Test
    void seatLabelsMatchTheServicePattern() {
        assertThat(Seats.labels(1_000)).allMatch(label -> label.matches("^[A-Z]{1,3}[0-9]{1,4}$"));
        assertThat(Seats.labels(1_000)).doesNotHaveDuplicates();
        assertThat(Seats.label(0)).isEqualTo("A1");
        assertThat(Seats.label(99)).isEqualTo("A100");
        assertThat(Seats.label(100)).isEqualTo("B1");
        assertThat(Seats.label(2_600)).isEqualTo("AA1");
    }

    @Test
    void percentilesUseNearestRank() {
        long[] sorted = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
        assertThat(Report.percentile(sorted, 50)).isEqualTo(5);
        assertThat(Report.percentile(sorted, 95)).isEqualTo(10);
        assertThat(Report.percentile(sorted, 99)).isEqualTo(10);
        assertThat(Report.percentile(new long[0], 99)).isZero();
    }

    @Test
    void parsesOptionsWithDefaults() {
        BurstConfig config = BurstConfig.parse(
                new String[] {"http://localhost:8080/", "--total", "500", "--storm-size", "50"},
                Map.of("ADMIN_API_KEY", "k"));

        assertThat(config.baseUrl().toString()).isEqualTo("http://localhost:8080");
        assertThat(config.total()).isEqualTo(500);
        assertThat(config.stormSize()).isEqualTo(50);
        assertThat(config.seats()).isEqualTo(BurstConfig.DEFAULT_SEATS);
        assertThat(config.adminKey()).isEqualTo("k");
    }

    @Test
    void rejectsMissingUrlOrAdminKey() {
        assertThatThrownBy(() -> BurstConfig.parse(new String[] {}, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BurstConfig.parse(new String[] {"http://x"}, Map.of()))
                .hasMessageContaining("admin key");
    }

    @Test
    void readsCounterValuesFromPrometheusText() {
        String scrape = """
                # TYPE seatres_reservations_declined_total counter
                seatres_reservations_declined_total{application="seat-service",reason="seat_taken"} 499.0
                seatres_reservations_declined_total{application="seat-service",reason="validation"} 2.0
                seatres_reservations_confirmed_total{application="seat-service"} 1.0
                """;

        assertThat(PrometheusText.value(scrape, "seatres_reservations_declined_total", Map.of("reason", "seat_taken")))
                .isEqualTo(499);
        assertThat(PrometheusText.value(scrape, "seatres_reservations_declined_total", Map.of()))
                .isEqualTo(501);
        assertThat(PrometheusText.value(scrape, "seatres_reservations_confirmed_total", Map.of()))
                .isEqualTo(1);
    }
}
