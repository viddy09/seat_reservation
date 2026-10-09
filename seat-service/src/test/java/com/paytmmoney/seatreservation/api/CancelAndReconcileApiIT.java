package com.paytmmoney.seatreservation.api;

import static com.paytmmoney.seatreservation.support.TestApi.errorCode;
import static com.paytmmoney.seatreservation.support.TestApi.json;
import static com.paytmmoney.seatreservation.support.TestApi.reservationId;
import static org.assertj.core.api.Assertions.assertThat;

import com.paytmmoney.seatreservation.security.TokenService;
import com.paytmmoney.seatreservation.support.IntegrationTest;
import com.paytmmoney.seatreservation.support.Invariants;
import com.paytmmoney.seatreservation.support.TestApi;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class CancelAndReconcileApiIT {

    @LocalServerPort
    private int port;

    @Autowired
    private TokenService tokens;

    @Autowired
    private JdbcClient jdbc;

    private TestApi api;
    private UUID showId;
    private String alice;
    private String bob;

    @BeforeEach
    void setUp() {
        api = new TestApi(port, tokens);
        showId = api.createShow(List.of("A1", "A2", "A3", "A4", "A5"), 2);
        String run = UUID.randomUUID().toString();
        alice = api.userToken("alice-" + run);
        bob = api.userToken("bob-" + run);
    }

    @AfterEach
    void booksBalance() {
        Invariants.assertHold(jdbc, showId);
        api.assertReconciled(showId);
    }

    @Test
    void ownerCancel_releasesSeatsAndQuota() {
        String id = reservationId(api.reserve(alice, showId, "k1", List.of("A1", "A2")));

        HttpResponse<String> cancelled = api.cancel(alice, id);

        assertThat(cancelled.statusCode()).isEqualTo(200);
        assertThat(json(cancelled).path("status").asString()).isEqualTo("cancelled");
        assertThat(json(api.http().get("/shows/" + showId))
                        .path("counts")
                        .path("available")
                        .asInt())
                .isEqualTo(5);
        // Quota is back: alice can again take her full limit of 2, with a new key.
        assertThat(api.reserve(alice, showId, "k2", List.of("A1", "A3")).statusCode())
                .isEqualTo(201);
    }

    @Test
    void cancelIsIdempotent() {
        String id = reservationId(api.reserve(alice, showId, "k1", List.of("A1")));

        assertThat(api.cancel(alice, id).statusCode()).isEqualTo(200);
        HttpResponse<String> again = api.cancel(alice, id);

        assertThat(again.statusCode()).isEqualTo(200);
        assertThat(json(again).path("status").asString()).isEqualTo("cancelled");
    }

    @Test
    void nonOwnerCancel_is404AndSeatStaysConfirmed() {
        String id = reservationId(api.reserve(alice, showId, "k1", List.of("A1")));

        HttpResponse<String> response = api.cancel(bob, id);

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(errorCode(response)).isEqualTo("not_found");
        assertThat(seatStatus("A1")).isEqualTo("confirmed");
        assertThat(api.cancel(bob, UUID.randomUUID().toString()).statusCode()).isEqualTo(404);
    }

    @Test
    void cancelledSeat_isRebookableByOthers() {
        String id = reservationId(api.reserve(alice, showId, "k1", List.of("A1")));
        assertThat(api.reserve(bob, showId, "b1", List.of("A1")).statusCode()).isEqualTo(409);

        api.cancel(alice, id);

        // Bob's earlier decline was not memoised (ADR-010): the same key now succeeds.
        assertThat(api.reserve(bob, showId, "b1", List.of("A1")).statusCode()).isEqualTo(201);
    }

    @Test
    void replayAfterCancel_returnsTheCancelledReservation() {
        HttpResponse<String> original = api.reserve(alice, showId, "k1", List.of("A1"));
        api.cancel(alice, reservationId(original));

        HttpResponse<String> replay = api.reserve(alice, showId, "k1", List.of("A1"));

        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(reservationId(replay)).isEqualTo(reservationId(original));
        assertThat(json(replay).path("status").asString()).isEqualTo("cancelled");
        assertThat(seatStatus("A1")).as("a replay never re-books").isEqualTo("available");
    }

    @Test
    void perUserLimitCountsHeldSeatsAcrossRequests() {
        assertThat(api.reserve(alice, showId, "k1", List.of("A1")).statusCode()).isEqualTo(201);
        assertThat(api.reserve(alice, showId, "k2", List.of("A2")).statusCode()).isEqualTo(201);

        HttpResponse<String> third = api.reserve(alice, showId, "k3", List.of("A3"));

        assertThat(third.statusCode()).isEqualTo(409);
        assertThat(errorCode(third)).isEqualTo("per_user_limit");
        assertThat(seatStatus("A3")).isEqualTo("available");
    }

    @Test
    void multiSeatIsAllOrNothing() {
        api.reserve(alice, showId, "k1", List.of("A3"));

        HttpResponse<String> response = api.reserve(bob, showId, "b1", List.of("A2", "A3"));

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(seatStatus("A2")).isEqualTo("available");
        // The declined attempt also returned bob's quota: he can still take 2.
        assertThat(api.reserve(bob, showId, "b2", List.of("A4", "A5")).statusCode())
                .isEqualTo(201);
    }

    @Test
    void reconcileReportsChecksAndNumbers() {
        api.reserve(alice, showId, "k1", List.of("A1", "A2"));

        JsonNode report = api.assertReconciled(showId);

        assertThat(report.path("checks").values().stream()
                        .map(c -> c.path("name").asString()))
                .contains(
                        "counts_sum_to_total",
                        "taken_seats_match_reservations",
                        "user_counts_match_taken_seats",
                        "no_user_over_limit",
                        "no_seat_in_two_active_reservations");
        assertThat(report.path("numbers").path("confirmed").asInt()).isEqualTo(2);
        assertThat(report.path("numbers").path("total_seats").asInt()).isEqualTo(5);
    }

    @Test
    void reconcileDetectsCorruption() {
        api.reserve(alice, showId, "k1", List.of("A1"));
        // Simulate a bug that bypassed the service: a counter out of step with the seats.
        jdbc.sql("UPDATE user_show_counts SET seat_count = seat_count + 1 WHERE show_id = ?")
                .param(showId)
                .update();

        JsonNode report = json(api.http().get("/shows/" + showId + "/reconcile"));

        assertThat(report.path("ok").asBoolean()).isFalse();
        jdbc.sql("UPDATE user_show_counts SET seat_count = seat_count - 1 WHERE show_id = ?")
                .param(showId)
                .update();
    }

    @Test
    void reconcileUnknownShow_is404() {
        assertThat(api.http().get("/shows/" + UUID.randomUUID() + "/reconcile").statusCode())
                .isEqualTo(404);
    }

    private String seatStatus(String label) {
        return jdbc.sql("SELECT status FROM seats WHERE show_id = ? AND seat_label = ?")
                .params(showId, label)
                .query(String.class)
                .single();
    }
}
