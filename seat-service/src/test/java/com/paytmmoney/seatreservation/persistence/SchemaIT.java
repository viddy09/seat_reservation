package com.paytmmoney.seatreservation.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.paytmmoney.seatreservation.support.IntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The schema's constraints are defence in depth: the database itself refuses an inconsistent seat. */
@IntegrationTest
class SchemaIT {

    @Autowired
    private JdbcClient jdbc;

    private UUID showId;

    @BeforeEach
    void createShow() {
        showId = UUID.randomUUID();
        jdbc.sql(
                        "INSERT INTO shows (id, name, price_paise, per_user_limit, total_seats) VALUES (?, 'Test', 25000, 4, 2)")
                .param(showId)
                .update();
        jdbc.sql("INSERT INTO seats (show_id, seat_label) SELECT ?, unnest(?::text[])")
                .param(showId)
                .param(new String[] {"A10", "A2"})
                .update();
    }

    @Test
    void migrationsCreatedAllTables() {
        List<String> tables = jdbc.sql(
                        "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' ORDER BY 1")
                .query(String.class)
                .list();

        assertThat(tables).contains("reservations", "seats", "shows", "user_show_counts");
    }

    @Test
    void seatCannotBeConfirmedWithoutReservation() {
        assertThatThrownBy(() -> jdbc.sql(
                                "UPDATE seats SET status = 'confirmed', user_id = 'u1' WHERE show_id = ? AND seat_label = 'A2'")
                        .param(showId)
                        .update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("seats_owner_iff_taken");
    }

    @Test
    void availableSeatCannotCarryReservation() {
        assertThatThrownBy(() -> jdbc.sql("UPDATE seats SET reservation_id = ? WHERE show_id = ? AND seat_label = 'A2'")
                        .param(UUID.randomUUID())
                        .param(showId)
                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void seatLabelsSortInByteOrder() {
        // COLLATE "C" must match Java's String.compareTo, which the global lock order relies on (ADR-017).
        List<String> labels = jdbc.sql("SELECT seat_label FROM seats WHERE show_id = ? ORDER BY seat_label")
                .param(showId)
                .query(String.class)
                .list();

        assertThat(labels).containsExactly("A10", "A2");
        assertThat(labels).isSortedAccordingTo(String::compareTo);
    }

    @Test
    void userSeatCountCannotGoNegative() {
        assertThatThrownBy(() -> jdbc.sql(
                                "INSERT INTO user_show_counts (show_id, user_id, seat_count) VALUES (?, 'u1', -1)")
                        .param(showId)
                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
