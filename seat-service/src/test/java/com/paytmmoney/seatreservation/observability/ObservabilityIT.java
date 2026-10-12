package com.paytmmoney.seatreservation.observability;

import static com.paytmmoney.seatreservation.support.TestApi.bearer;
import static com.paytmmoney.seatreservation.support.TestApi.reservationId;
import static org.assertj.core.api.Assertions.assertThat;

import com.paytmmoney.seatreservation.security.TokenService;
import com.paytmmoney.seatreservation.support.Concurrently;
import com.paytmmoney.seatreservation.support.IntegrationTest;
import com.paytmmoney.seatreservation.support.PrometheusText;
import com.paytmmoney.seatreservation.support.TestApi;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/** Metrics must reconcile with what the API returned and with the database. */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class ObservabilityIT {

    private static final String CONFIRMED = "seatres_reservations_confirmed_total";
    private static final String DECLINED = "seatres_reservations_declined_total";
    private static final String CANCELLED = "seatres_reservations_cancelled_total";

    @LocalServerPort
    private int port;

    @Autowired
    private TokenService tokens;

    @Autowired
    private JdbcClient jdbc;

    private TestApi api;
    private String run;

    @BeforeEach
    void setUp() {
        api = new TestApi(port, tokens);
        run = UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    void outcomeCountersMatchEveryResponse() {
        UUID showId = api.createShow(List.of("A1", "A2", "A3", "A4"), 2);
        String alice = api.userToken("alice-" + run);
        String bob = api.userToken("bob-" + run);
        String before = scrape();

        HttpResponse<String> created = api.reserve(alice, showId, "k1", List.of("A1"));
        api.reserve(alice, showId, "k1", List.of("A1")); // replay
        api.reserve(alice, showId, "k1", List.of("A2")); // key reused
        api.reserve(bob, showId, "b1", List.of("A1")); // seat taken
        api.reserve(bob, showId, "b2", List.of("A2", "A3", "A4")); // over limit
        api.reserve(bob, showId, "b3", List.of("Z9")); // unknown seat -> validation
        api.reserve(bob, UUID.randomUUID(), "b4", List.of("A1")); // not found
        api.http().post("/shows/" + showId + "/reserve", "{\"seats\":[\"A2\"]}", Map.of("Idempotency-Key", "x"));
        api.cancel(alice, reservationId(created));
        api.cancel(alice, reservationId(created)); // idempotent: not counted twice

        String after = scrape();
        assertThat(delta(before, after, CONFIRMED, Map.of())).isEqualTo(1);
        assertThat(delta(before, after, CANCELLED, Map.of())).isEqualTo(1);
        Map<String, Long> expectedDeclines = Map.of(
                "idempotent_replay", 1L,
                "idempotency_key_reused", 1L,
                "seat_taken", 1L,
                "per_user_limit", 1L,
                "validation", 1L,
                "not_found", 1L,
                "unauthorized", 1L,
                "contention", 0L,
                "server_busy", 0L);
        expectedDeclines.forEach((reason, count) -> assertThat(delta(before, after, DECLINED, Map.of("reason", reason)))
                .as(reason)
                .isEqualTo(count));
    }

    @Test
    void hotSeatStormCountersReconcileExactly() {
        int users = 200;
        UUID showId = api.createShow(List.of("S1"), 4);
        List<String> userTokens = IntStream.range(0, users)
                .mapToObj(i -> api.userToken("m-" + run + "-" + i))
                .toList();
        String before = scrape();

        List<HttpResponse<String>> responses =
                Concurrently.run(users, i -> api.reserve(userTokens.get(i), showId, "k", List.of("S1")));

        String after = scrape();
        long created = responses.stream().filter(r -> r.statusCode() == 201).count();
        long taken = responses.stream().filter(r -> r.statusCode() == 409).count();
        assertThat(created).isEqualTo(1);
        assertThat(delta(before, after, CONFIRMED, Map.of())).isEqualTo(created);
        assertThat(delta(before, after, DECLINED, Map.of("reason", "seat_taken")))
                .isEqualTo(taken);
        assertThat(delta(
                        before,
                        after,
                        "http_requests_total",
                        Map.of("route", "/shows/{showId}/reserve", "method", "POST", "code", "409")))
                .isEqualTo(taken);
    }

    @Test
    void seatGaugesComeFromTheDatabase() throws InterruptedException {
        UUID showId = api.createShow(List.of("G1", "G2", "G3"), 4);
        api.reserve(api.userToken("g-" + run), showId, "k", List.of("G1", "G2"));
        // The collector caches for at most one second.
        Thread.sleep(SeatGaugeCollector.MAX_AGE.toMillis() + 100);

        String scrape = scrape();
        Map<String, String> show = Map.of("show_id", showId.toString());
        assertThat(PrometheusText.value(scrape, "seatres_seats", with(show, "status", "confirmed")))
                .isEqualTo(2);
        assertThat(PrometheusText.value(scrape, "seatres_seats", with(show, "status", "available")))
                .isEqualTo(1);
        assertThat(PrometheusText.value(scrape, "seatres_seats", with(show, "status", "held")))
                .isZero();
        assertThat(PrometheusText.value(scrape, "seatres_seats_available", show))
                .isEqualTo(1);
        assertThat(PrometheusText.value(scrape, "seatres_seats", show))
                .as("sum over states")
                .isEqualTo(3);
    }

    @Test
    void poolAndRetryMetricsUseRunbookNames() {
        api.http().get("/shows/" + UUID.randomUUID()); // make sure the main pool has been used
        String scrape = scrape();

        assertThat(scrape)
                .contains("db_pool_acquired{")
                .contains("db_pool_idle{")
                .contains("db_pool_connections{")
                .contains("db_pool_pending{")
                .contains("db_pool_wait_seconds_count{")
                .contains("db_pool_timeouts_total{")
                .contains("pool=\"main\"")
                .contains("pool=\"obs\"")
                .doesNotContain("hikaricp_connections_active");
        assertThat(PrometheusText.value(scrape, "db_pool_max", Map.of("pool", "main")))
                .isEqualTo(20);
    }

    @Test
    void routeLabelsAreTemplatesNeverRawPaths() {
        UUID showId = api.createShow(List.of("R1"), 4);
        api.http().get("/shows/" + showId);
        api.http().get("/definitely/not/a/route/" + UUID.randomUUID());

        String scrape = scrape();
        assertThat(scrape)
                .contains("route=\"/shows/{showId}\"")
                .contains("route=\"unmatched\"")
                .doesNotContain(showId.toString() + "\",")
                .contains("http_request_duration_seconds_bucket{");
    }

    @Test
    void accessLogIsOneJsonLinePerRequestWithContext(CapturedOutput output) {
        UUID showId = api.createShow(List.of("L1", "L2"), 4);
        String token = api.userToken("logger-" + run);
        Map<String, String> headers = new LinkedHashMap<>(bearer(token));
        headers.put("Idempotency-Key", "log-key");
        headers.put("X-Request-ID", "req-" + run);

        api.http().post("/shows/" + showId + "/reserve", "{\"seats\":[\"L1\"]}", headers);

        String line = output.getOut()
                .lines()
                .filter(l -> l.contains("\"req-" + run + "\"") && l.contains("\"logger_name\":\"access\""))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no access log line for the request"));
        JsonNode json = TestApi.JSON.readTree(line);
        assertThat(json.path("request_id").asString()).isEqualTo("req-" + run);
        assertThat(json.path("method").asString()).isEqualTo("POST");
        assertThat(json.path("route").asString()).isEqualTo("/shows/{showId}/reserve");
        assertThat(json.path("status").asInt()).isEqualTo(201);
        assertThat(json.has("latency_ms")).isTrue();
        assertThat(json.path("outcome").asString()).isEqualTo("confirmed");
        assertThat(json.path("user_id").asString()).isEqualTo("logger-" + run);
        assertThat(json.path("show_id").asString()).isEqualTo(showId.toString());
        assertThat(json.path("reservation_id").asString()).isNotBlank();
        assertThat(output.getOut()).as("tokens are never logged").doesNotContain(token);
    }

    private String scrape() {
        HttpResponse<String> response = api.http().get("/metrics");
        assertThat(response.statusCode()).isEqualTo(200);
        return response.body();
    }

    private static long delta(String before, String after, String metric, Map<String, String> labels) {
        return PrometheusText.value(after, metric, labels) - PrometheusText.value(before, metric, labels);
    }

    private static Map<String, String> with(Map<String, String> base, String key, String value) {
        Map<String, String> labels = new LinkedHashMap<>(base);
        labels.put(key, value);
        return labels;
    }
}
