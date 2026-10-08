package com.paytmmoney.seatreservation.api;

import static com.paytmmoney.seatreservation.support.TestApi.bearer;
import static com.paytmmoney.seatreservation.support.TestApi.errorCode;
import static com.paytmmoney.seatreservation.support.TestApi.json;
import static org.assertj.core.api.Assertions.assertThat;

import com.paytmmoney.seatreservation.security.TokenService;
import com.paytmmoney.seatreservation.support.IntegrationTest;
import com.paytmmoney.seatreservation.support.TestApi;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class ShowAndAuthApiIT {

    private static final String VALID_SHOW =
            "{\"name\":\"Gala\",\"price_paise\":25000,\"seats\":[\"A2\",\"A1\"],\"per_user_limit\":2}";

    @LocalServerPort
    private int port;

    @Autowired
    private TokenService tokens;

    private TestApi api;

    @BeforeEach
    void setUp() {
        api = new TestApi(port, tokens);
    }

    @Test
    void userToken_isIssuedWithoutAdminKey() {
        HttpResponse<String> response = api.http().post("/auth/token", "{\"user_id\":\"u-1\"}", Map.of());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json(response).path("token").asString()).isNotBlank();
    }

    @Test
    void adminToken_requiresAdminKey() {
        String body = "{\"user_id\":\"ops\",\"role\":\"admin\"}";

        HttpResponse<String> withoutKey = api.http().post("/auth/token", body, Map.of());
        HttpResponse<String> wrongKey = api.http().post("/auth/token", body, Map.of("X-Admin-Key", "nope"));
        HttpResponse<String> rightKey =
                api.http().post("/auth/token", body, Map.of("X-Admin-Key", IntegrationTest.ADMIN_API_KEY));

        assertThat(withoutKey.statusCode()).isEqualTo(401);
        assertThat(errorCode(withoutKey)).isEqualTo("invalid_admin_key");
        assertThat(wrongKey.statusCode()).isEqualTo(401);
        assertThat(rightKey.statusCode()).isEqualTo(200);

        String adminToken = json(rightKey).path("token").asString();
        assertThat(api.http().post("/shows", VALID_SHOW, bearer(adminToken)).statusCode())
                .isEqualTo(201);
    }

    @Test
    void invalidTokenRequest_returns400() {
        assertThat(api.http()
                        .post("/auth/token", "{\"user_id\":\"has space\"}", Map.of())
                        .statusCode())
                .isEqualTo(400);
        assertThat(api.http()
                        .post("/auth/token", "{\"user_id\":\"u\",\"role\":\"root\"}", Map.of())
                        .statusCode())
                .isEqualTo(400);
    }

    @Test
    void createShow_requiresAdmin() {
        assertThat(api.http().post("/shows", VALID_SHOW, Map.of()).statusCode()).isEqualTo(401);

        HttpResponse<String> asUser = api.http().post("/shows", VALID_SHOW, bearer(api.userToken("u-2")));
        assertThat(asUser.statusCode()).isEqualTo(403);
        assertThat(errorCode(asUser)).isEqualTo("forbidden");

        HttpResponse<String> forged = api.http().post("/shows", VALID_SHOW, bearer(api.adminToken() + "x"));
        assertThat(forged.statusCode()).isEqualTo(401);
    }

    @Test
    void createShow_returnsAllSeatsAvailable() {
        HttpResponse<String> response = api.http().post("/shows", VALID_SHOW, bearer(api.adminToken()));

        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode body = json(response);
        assertThat(body.path("total_seats").asInt()).isEqualTo(2);
        assertThat(body.path("per_user_limit").asInt()).isEqualTo(2);
        assertThat(body.path("counts").path("available").asInt()).isEqualTo(2);
        assertThat(body.path("seats").values().stream()
                        .map(seat -> seat.path("label").asString()))
                .containsExactly("A1", "A2");

        HttpResponse<String> fetched =
                api.http().get("/shows/" + body.path("id").asString());
        assertThat(fetched.statusCode()).isEqualTo(200);
        assertThat(json(fetched).path("seats").values().stream()
                        .map(seat -> seat.path("status").asString()))
                .containsOnly("available");
    }

    @Test
    void perUserLimitDefaultsToFour() {
        UUID id = api.createShowWithoutLimit();
        assertThat(json(api.http().get("/shows/" + id)).path("per_user_limit").asInt())
                .isEqualTo(4);
    }

    @Test
    void createShow_validationFailuresAre422() {
        String admin = api.adminToken();
        List<String> invalid = List.of(
                "{\"name\":\"x\",\"price_paise\":100,\"seats\":[\"A1\",\"A1\"]}",
                "{\"name\":\"x\",\"price_paise\":100,\"seats\":[\"a1\"]}",
                "{\"name\":\"x\",\"price_paise\":100,\"seats\":[]}",
                "{\"name\":\"x\",\"price_paise\":0,\"seats\":[\"A1\"]}",
                "{\"name\":\"x\",\"price_paise\":-5,\"seats\":[\"A1\"]}",
                "{\"name\":\"\",\"price_paise\":100,\"seats\":[\"A1\"]}",
                "{\"name\":\"x\",\"price_paise\":100,\"seats\":[\"A1\"],\"per_user_limit\":0}",
                "{\"name\":\"x\",\"seats\":[\"A1\"]}");

        for (String body : invalid) {
            HttpResponse<String> response = api.http().post("/shows", body, bearer(admin));
            assertThat(response.statusCode()).as(body).isEqualTo(422);
            assertThat(errorCode(response)).as(body).isEqualTo("validation");
        }
    }

    @Test
    void nonIntegerMoney_isRejectedNotTruncated() {
        String admin = api.adminToken();
        for (String price : List.of("250.5", "\"250\"", "1e3")) {
            String body = "{\"name\":\"x\",\"price_paise\":" + price + ",\"seats\":[\"A1\"]}";
            HttpResponse<String> response = api.http().post("/shows", body, bearer(admin));
            assertThat(response.statusCode()).as(price).isEqualTo(400);
            assertThat(errorCode(response)).as(price).isEqualTo("invalid_json");
        }
    }

    @Test
    void malformedJson_returns400() {
        HttpResponse<String> response = api.http().post("/shows", "{not json", bearer(api.adminToken()));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(errorCode(response)).isEqualTo("invalid_json");
    }

    @Test
    void unknownOrMalformedShowId_returns404() {
        assertThat(api.http().get("/shows/" + UUID.randomUUID()).statusCode()).isEqualTo(404);
        assertThat(api.http().get("/shows/not-a-uuid").statusCode()).isEqualTo(404);
    }
}
