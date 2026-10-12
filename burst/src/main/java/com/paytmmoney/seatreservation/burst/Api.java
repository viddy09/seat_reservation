package com.paytmmoney.seatreservation.burst;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.MissingNode;

/**
 * HTTP client for the seat service. Keep-alive HTTP/1.1 connections, and a semaphore that caps requests in flight at
 * {@code --concurrency} across every scenario.
 */
final class Api {

    static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    /** Generous: the server queues for a pool connection for up to ~20s rather than failing. */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(90);

    static final int TRANSPORT_ERROR = -1;

    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(CONNECT_TIMEOUT)
            .build();
    private static final int CONNECT_ATTEMPTS = 5;
    private static final long CONNECT_BACKOFF_MILLIS = 50;

    private final URI base;
    private final Semaphore inFlight;
    private final AtomicLong connectRetries = new AtomicLong();

    Api(URI base, int concurrency) {
        this.base = base;
        this.inFlight = new Semaphore(concurrency);
    }

    record Response(int status, JsonNode body, String rawBody, long latencyNanos, String transportError) {

        String errorCode() {
            return body.path("error").path("code").asString("");
        }

        boolean replayed() {
            return status == Http.OK;
        }

        String reservationId() {
            return body.path("reservation_id").asString("");
        }

        boolean isTransportError() {
            return status == TRANSPORT_ERROR;
        }
    }

    Response get(String path, String token) {
        return send("GET", path, token, Map.of(), null);
    }

    Response post(String path, String token, Map<String, String> headers, Object body) {
        return send("POST", path, token, headers, body);
    }

    Response reserve(String token, String showId, String key, List<String> seats, Map<String, Object> extraBody) {
        Map<String, Object> body = new LinkedHashMap<>(extraBody);
        body.put("seats", seats);
        return post("/shows/" + showId + "/reserve", token, Map.of("Idempotency-Key", key), body);
    }

    Response cancel(String token, String reservationId) {
        return post("/reservations/" + reservationId + "/cancel", token, Map.of(), null);
    }

    String scrapeMetrics() {
        Response response = get("/metrics", null);
        if (response.status() != Http.OK) {
            throw new IllegalStateException("GET /metrics returned " + response.status());
        }
        return response.rawBody();
    }

    private Response send(String method, String path, String token, Map<String, String> headers, Object body) {
        HttpRequest.Builder request = HttpRequest.newBuilder(base.resolve(path))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json");
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        headers.forEach(request::header);
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        }
        inFlight.acquireUninterruptibly();
        long start = System.nanoTime();
        try {
            HttpResponse<String> response = sendWithConnectRetry(request.build());
            long latency = System.nanoTime() - start;
            return new Response(response.statusCode(), parse(response.body()), response.body(), latency, null);
        } catch (IOException e) {
            return new Response(
                    TRANSPORT_ERROR, MissingNode.getInstance(), "", System.nanoTime() - start, e.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Response(
                    TRANSPORT_ERROR, MissingNode.getInstance(), "", System.nanoTime() - start, "interrupted");
        } finally {
            inFlight.release();
        }
    }

    /**
     * A refused connect means the request never left this machine, so retrying it can't duplicate anything. Some
     * client OSes (Windows) cap the listen backlog they get from the server below a large simultaneous burst. Retries
     * are counted and printed; a connect that still fails counts as a transport error.
     */
    private HttpResponse<String> sendWithConnectRetry(HttpRequest request) throws IOException, InterruptedException {
        for (int attempt = 1; ; attempt++) {
            try {
                return client.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (ConnectException e) {
                if (attempt >= CONNECT_ATTEMPTS) {
                    throw e;
                }
                connectRetries.incrementAndGet();
                Thread.sleep(Duration.ofMillis(CONNECT_BACKOFF_MILLIS * attempt));
            }
        }
    }

    long connectRetries() {
        return connectRetries.get();
    }

    /** Short description of a response for error messages. */
    static String describe(Response response) {
        if (response.isTransportError()) {
            return response.transportError();
        }
        String body = response.rawBody();
        return body.length() > DESCRIBE_LIMIT ? body.substring(0, DESCRIBE_LIMIT) + "..." : body;
    }

    private static final int DESCRIBE_LIMIT = 300;

    private static JsonNode parse(String body) {
        if (body == null || body.isBlank() || !(body.startsWith("{") || body.startsWith("["))) {
            return MissingNode.getInstance();
        }
        try {
            return JSON.readTree(body);
        } catch (RuntimeException e) {
            return MissingNode.getInstance();
        }
    }
}
