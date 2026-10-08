package com.paytmmoney.seatreservation.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.paytmmoney.seatreservation.security.Role;
import com.paytmmoney.seatreservation.security.TokenService;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Speaks the public API the way a client would; tokens are minted directly to keep tests fast. */
public final class TestApi {

    public static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpTestClient http;
    private final TokenService tokens;

    public TestApi(int port, TokenService tokens) {
        this.http = new HttpTestClient(port);
        this.tokens = tokens;
    }

    public HttpTestClient http() {
        return http;
    }

    public String userToken(String userId) {
        return tokens.issue(userId, Role.USER).token();
    }

    public String adminToken() {
        return tokens.issue("ops-admin", Role.ADMIN).token();
    }

    public static Map<String, String> bearer(String token) {
        return Map.of("Authorization", "Bearer " + token);
    }

    /** Creates a show via the admin API and returns its id. */
    public UUID createShow(List<String> seats, int perUserLimit, long pricePaise) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Test show");
        body.put("price_paise", pricePaise);
        body.put("per_user_limit", perUserLimit);
        body.put("seats", seats);
        HttpResponse<String> response = http.post("/shows", JSON.writeValueAsString(body), bearer(adminToken()));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return UUID.fromString(json(response).path("id").asString());
    }

    public UUID createShowWithoutLimit() {
        String body = "{\"name\":\"Default limit\",\"price_paise\":100,\"seats\":[\"A1\"]}";
        HttpResponse<String> response = http.post("/shows", body, bearer(adminToken()));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return UUID.fromString(json(response).path("id").asString());
    }

    public UUID createShow(List<String> seats, int perUserLimit) {
        return createShow(seats, perUserLimit, 25_000);
    }

    public HttpResponse<String> reserve(String token, UUID showId, String key, List<String> seats) {
        String body = JSON.writeValueAsString(Map.of("seats", seats));
        Map<String, String> headers = new LinkedHashMap<>(bearer(token));
        headers.put("Idempotency-Key", key);
        return http.post("/shows/" + showId + "/reserve", body, headers);
    }

    public static JsonNode json(HttpResponse<String> response) {
        return JSON.readTree(response.body());
    }

    public static String errorCode(HttpResponse<String> response) {
        return json(response).path("error").path("code").asString();
    }
}
