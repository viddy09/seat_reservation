package com.paytmmoney.seatreservation.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.paytmmoney.seatreservation.support.HttpTestClient;
import com.paytmmoney.seatreservation.support.IntegrationTest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
class OperationalEndpointsIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort
    private int port;

    private HttpTestClient http;

    @BeforeEach
    void setUp() {
        http = new HttpTestClient(port);
    }

    @Test
    void healthz_isUpWithoutCheckingDependencies() {
        HttpResponse<String> response = http.get("/healthz");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"");
    }

    @Test
    void readyz_isUpWhenDatabaseReachable() {
        HttpResponse<String> response = http.get("/readyz");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"");
    }

    @Test
    void actuatorEquivalentsStillWork() {
        assertThat(http.get("/actuator/health/liveness").statusCode()).isEqualTo(200);
        assertThat(http.get("/actuator/health/readiness").statusCode()).isEqualTo(200);
        assertThat(http.get("/actuator/prometheus").statusCode()).isEqualTo(200);
    }

    @Test
    void metrics_servesPrometheusText() {
        HttpResponse<String> response = http.get("/metrics");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type"))
                .hasValueSatisfying(type -> assertThat(type).startsWith("text/plain"));
        assertThat(response.body()).contains("jvm_memory_used_bytes");
    }

    @Test
    void unknownRoute_returns404Envelope() {
        HttpResponse<String> response = http.get("/no/such/route");

        assertThat(response.statusCode()).isEqualTo(404);
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.path("error").path("code").asString()).isEqualTo("not_found");
        assertThat(body.path("request_id").asString())
                .isNotBlank()
                .isEqualTo(response.headers().firstValue(RequestIdFilter.HEADER).orElseThrow());
    }

    @Test
    void wrongMethod_returns405Envelope() {
        HttpResponse<String> response = http.post("/metrics", "{}", Map.of());

        assertThat(response.statusCode()).isEqualTo(405);
        assertThat(JSON.readTree(response.body()).path("error").path("code").asString())
                .isEqualTo("method_not_allowed");
    }

    @Test
    void requestId_isEchoedWhenSafe() {
        HttpResponse<String> response = http.get("/no/such/route", Map.of(RequestIdFilter.HEADER, "trace-abc_123"));

        assertThat(response.headers().firstValue(RequestIdFilter.HEADER)).hasValue("trace-abc_123");
        assertThat(JSON.readTree(response.body()).path("request_id").asString()).isEqualTo("trace-abc_123");
    }

    @Test
    void requestId_isReplacedWhenUnsafe() {
        HttpResponse<String> response = http.get("/healthz", Map.of(RequestIdFilter.HEADER, "bad id with spaces"));

        assertThat(response.headers().firstValue(RequestIdFilter.HEADER))
                .hasValueSatisfying(
                        id -> assertThat(id).isNotEqualTo("bad id with spaces").hasSize(36));
    }
}
