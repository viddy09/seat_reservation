package com.paytmmoney.seatreservation.api;

import static com.paytmmoney.seatreservation.support.TestApi.bearer;
import static com.paytmmoney.seatreservation.support.TestApi.errorCode;
import static com.paytmmoney.seatreservation.support.TestApi.json;
import static org.assertj.core.api.Assertions.assertThat;

import com.paytmmoney.seatreservation.security.TokenService;
import com.paytmmoney.seatreservation.support.IntegrationTest;
import com.paytmmoney.seatreservation.support.Invariants;
import com.paytmmoney.seatreservation.support.TestApi;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/** Happy and decline paths of reserve and reservation lookup, one request at a time. */
@IntegrationTest
class ReserveApiIT {

    @LocalServerPort
    private int port;

    @Autowired
    private TokenService tokens;

    @Autowired
    private JdbcClient jdbc;

    private TestApi api;
    private UUID showId;
    private String alice;

    @BeforeEach
    void setUp() {
        api = new TestApi(port, tokens);
        showId = api.createShow(List.of("A1", "A2", "A3", "A12", "A13", "B1"), 4, 25_000);
        alice = api.userToken("alice-" + UUID.randomUUID());
    }

    @AfterEach
    void invariantsHold() {
        Invariants.assertHold(jdbc, showId);
    }

    @Test
    void reserve_returns201AndConfirmsSeats() {
        HttpResponse<String> response = api.reserve(alice, showId, "k1", List.of("A2", "A1"));

        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode body = json(response);
        assertThat(body.path("status").asString()).isEqualTo("confirmed");
        assertThat(body.path("amount_paise").asLong()).isEqualTo(50_000);
        assertThat(body.path("seats").values().stream().map(JsonNode::asString)).containsExactly("A1", "A2");
        assertThat(response.headers().firstValue("Location")).isPresent();

        JsonNode show = json(api.http().get("/shows/" + showId));
        assertThat(show.path("counts").path("confirmed").asInt()).isEqualTo(2);
        assertThat(show.path("counts").path("available").asInt()).isEqualTo(4);
        assertThat(show.path("counts").path("held").asInt()).isZero();
        assertThat(show.path("counts").path("total").asInt()).isEqualTo(6);
    }

    @Test
    void replay_returns200WithOriginalReservation() {
        HttpResponse<String> first = api.reserve(alice, showId, "k1", List.of("A12", "A13"));
        HttpResponse<String> replay = api.reserve(alice, showId, "k1", List.of("A13", "A12"));

        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(replay.headers().firstValue(ReservationController.REPLAYED_HEADER))
                .hasValue("true");
        assertThat(json(replay).path("reservation_id").asString())
                .isEqualTo(json(first).path("reservation_id").asString());
    }

    @Test
    void sameKeyDifferentBody_returns409() {
        api.reserve(alice, showId, "k1", List.of("A1"));

        HttpResponse<String> response = api.reserve(alice, showId, "k1", List.of("A2"));

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(errorCode(response)).isEqualTo("idempotency_key_reused");
    }

    @Test
    void keysAreScopedPerUser() {
        String bob = api.userToken("bob-" + UUID.randomUUID());

        assertThat(api.reserve(alice, showId, "shared", List.of("A1")).statusCode())
                .isEqualTo(201);
        assertThat(api.reserve(bob, showId, "shared", List.of("A2")).statusCode())
                .isEqualTo(201);
    }

    @Test
    void takenSeat_returns409WithTheSeats() {
        api.reserve(alice, showId, "k1", List.of("A1"));
        String bob = api.userToken("bob-" + UUID.randomUUID());

        HttpResponse<String> response = api.reserve(bob, showId, "k2", List.of("A1", "A2"));

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(errorCode(response)).isEqualTo("seat_taken");
        assertThat(json(response).path("error").path("details").path("seats").values().stream()
                        .map(JsonNode::asString))
                .containsExactly("A1");
        // All-or-nothing: A2 was not taken.
        assertThat(seatStatus("A2")).isEqualTo("available");
    }

    @Test
    void missingKey_returns400() {
        HttpResponse<String> response =
                api.http().post("/shows/" + showId + "/reserve", "{\"seats\":[\"A1\"]}", bearer(alice));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(errorCode(response)).isEqualTo("idempotency_key_required");
    }

    @Test
    void headerAndBodyKeyDiffer_returns422() {
        Map<String, String> headers = new LinkedHashMap<>(bearer(alice));
        headers.put("Idempotency-Key", "one");

        HttpResponse<String> response = api.http()
                .post("/shows/" + showId + "/reserve", "{\"seats\":[\"A1\"],\"idempotency_key\":\"two\"}", headers);

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(errorCode(response)).isEqualTo("idempotency_key_mismatch");
    }

    @Test
    void bodyKeyAlone_isAccepted() {
        HttpResponse<String> response = api.http()
                .post(
                        "/shows/" + showId + "/reserve",
                        "{\"seats\":[\"A1\"],\"idempotency_key\":\"body\"}",
                        bearer(alice));

        assertThat(response.statusCode()).isEqualTo(201);
    }

    @Test
    void duplicateOrEmptySeats_return422() {
        assertThat(api.reserve(alice, showId, "k1", List.of("A1", "A1")).statusCode())
                .isEqualTo(422);
        assertThat(api.reserve(alice, showId, "k2", List.of()).statusCode()).isEqualTo(422);
    }

    @Test
    void unknownSeat_returns422() {
        HttpResponse<String> response = api.reserve(alice, showId, "k1", List.of("A1", "Z99"));

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(errorCode(response)).isEqualTo("unknown_seat");
        assertThat(seatStatus("A1")).isEqualTo("available");
    }

    @Test
    void moreSeatsThanLimit_returns409PerUserLimit() {
        HttpResponse<String> response = api.reserve(alice, showId, "k1", List.of("A1", "A2", "A3", "A12", "A13"));

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(errorCode(response)).isEqualTo("per_user_limit");
    }

    @Test
    void unknownShow_returns404() {
        HttpResponse<String> response = api.reserve(alice, UUID.randomUUID(), "k1", List.of("A1"));

        assertThat(response.statusCode()).isEqualTo(404);
    }

    @Test
    void noToken_returns401Envelope() {
        HttpResponse<String> response = api.http()
                .post("/shows/" + showId + "/reserve", "{\"seats\":[\"A1\"]}", Map.of("Idempotency-Key", "k"));

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(errorCode(response)).isEqualTo("unauthorized");
        assertThat(json(response).path("request_id").asString()).isNotBlank();
    }

    @Test
    void spoofedUserIdInBody_isIgnored() {
        HttpResponse<String> response = api.http()
                .post(
                        "/shows/" + showId + "/reserve",
                        "{\"seats\":[\"A1\"],\"user_id\":\"mallory\",\"idempotency_key\":\"k\"}",
                        bearer(alice));

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(json(response).path("user_id").asString()).startsWith("alice-");
    }

    @Test
    void reservationIsVisibleOnlyToItsOwner() {
        String reservationId = json(api.reserve(alice, showId, "k1", List.of("B1")))
                .path("reservation_id")
                .asString();
        String bob = api.userToken("bob-" + UUID.randomUUID());

        assertThat(api.http()
                        .get("/reservations/" + reservationId, bearer(alice))
                        .statusCode())
                .isEqualTo(200);
        assertThat(api.http().get("/reservations/" + reservationId, bearer(bob)).statusCode())
                .isEqualTo(404);
        assertThat(api.http()
                        .get("/reservations/" + UUID.randomUUID(), bearer(alice))
                        .statusCode())
                .isEqualTo(404);
    }

    private String seatStatus(String label) {
        return jdbc.sql("SELECT status FROM seats WHERE show_id = ? AND seat_label = ?")
                .params(showId, label)
                .query(String.class)
                .single();
    }
}
