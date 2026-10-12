package com.paytmmoney.seatreservation.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/** Thin wrapper over the JDK {@link HttpClient}: tests talk to the real server exactly as a client would. */
public final class HttpTestClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final int CONNECT_ATTEMPTS = 5;
    private static final long CONNECT_BACKOFF_MILLIS = 50;

    // HTTP/1.1 explicitly: the JDK default (HTTP/2) would send an h2c upgrade on every plain-http request.
    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(TIMEOUT)
            .build();
    private final String baseUrl;

    public HttpTestClient(int port) {
        this.baseUrl = "http://localhost:" + port;
    }

    public HttpResponse<String> get(String path) {
        return get(path, Map.of());
    }

    public HttpResponse<String> get(String path, Map<String, String> headers) {
        return send(builder(path, headers).GET().build());
    }

    public HttpResponse<String> post(String path, String json, Map<String, String> headers) {
        HttpRequest request = builder(path, headers)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        return send(request);
    }

    private HttpRequest.Builder builder(String path, Map<String, String> headers) {
        HttpRequest.Builder builder =
                HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(TIMEOUT);
        headers.forEach(builder::header);
        return builder;
    }

    /**
     * Retries only a refused connect: the request never left the client, so a retry can't duplicate anything. Needed
     * on Windows, which caps the listen backlog (~200) below a 500-connection start-gate burst; Linux doesn't.
     */
    private HttpResponse<String> send(HttpRequest request) {
        for (int attempt = 1; ; attempt++) {
            try {
                return client.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (ConnectException e) {
                if (attempt >= CONNECT_ATTEMPTS) {
                    throw new UncheckedIOException(e);
                }
                pause(attempt);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }

    private static void pause(int attempt) {
        try {
            Thread.sleep(Duration.ofMillis(CONNECT_BACKOFF_MILLIS * attempt));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
