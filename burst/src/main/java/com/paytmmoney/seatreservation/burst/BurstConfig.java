package com.paytmmoney.seatreservation.burst;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

/** Command-line settings. Defaults: ~20k requests, 1000 seats, 5 hot seats × 500 users each. */
record BurstConfig(
        URI baseUrl,
        int concurrency,
        int total,
        int seats,
        int hotSeats,
        int stormSize,
        int users,
        String adminKey,
        int perUserLimit) {

    static final int DEFAULT_CONCURRENCY = 500;
    static final int DEFAULT_TOTAL = 20_000;
    static final int DEFAULT_SEATS = 1_000;
    static final int DEFAULT_HOT_SEATS = 5;
    static final int DEFAULT_STORM_SIZE = 500;
    static final int DEFAULT_USERS = 2_000;
    static final int PER_USER_LIMIT = 4;

    /** Seats set aside for the scripted scenarios (idempotency, limit, spoof, cancel + re-book). */
    static final int SCENARIO_SEATS = 40;

    static final String USAGE = """
            Usage: burst.sh <BASE_URL> [options]

              --concurrency N   max requests in flight            (default 500)
              --total N         total requests to send            (default 20000)
              --seats N         seats in the show                 (default 1000)
              --hot-seats N     seats targeted by storms          (default 5)
              --storm-size N    users per hot seat                (default 500)
              --users N         distinct users                    (default 2000)
              --admin-key KEY   X-Admin-Key for the admin token   (default: $ADMIN_API_KEY)

            Exit status: 0 all checks passed, 1 a check failed, 2 usage or setup error.
            """;

    static BurstConfig parse(String[] args, Map<String, String> env) {
        if (args.length == 0 || args[0].startsWith("--")) {
            throw new IllegalArgumentException("BASE_URL is required");
        }
        Map<String, String> options = new HashMap<>();
        for (int i = 1; i < args.length; i += 2) {
            if (!args[i].startsWith("--") || i + 1 >= args.length) {
                throw new IllegalArgumentException("Expected --option value, got: " + args[i]);
            }
            options.put(args[i].substring(2), args[i + 1]);
        }
        String base = args[0].endsWith("/") ? args[0].substring(0, args[0].length() - 1) : args[0];
        BurstConfig config = new BurstConfig(
                URI.create(base),
                intOption(options, "concurrency", DEFAULT_CONCURRENCY),
                intOption(options, "total", DEFAULT_TOTAL),
                intOption(options, "seats", DEFAULT_SEATS),
                intOption(options, "hot-seats", DEFAULT_HOT_SEATS),
                intOption(options, "storm-size", DEFAULT_STORM_SIZE),
                intOption(options, "users", DEFAULT_USERS),
                options.getOrDefault("admin-key", env.getOrDefault("ADMIN_API_KEY", "")),
                PER_USER_LIMIT);
        config.validate();
        return config;
    }

    int stampedeSeats() {
        return seats - hotSeats - SCENARIO_SEATS;
    }

    private void validate() {
        if (adminKey.isBlank()) {
            throw new IllegalArgumentException("An admin key is required: --admin-key or $ADMIN_API_KEY");
        }
        if (stampedeSeats() < PER_USER_LIMIT) {
            throw new IllegalArgumentException("--seats must exceed --hot-seats + " + SCENARIO_SEATS + " + 4");
        }
        if (users <= stormSize) {
            throw new IllegalArgumentException("--users must exceed --storm-size (storm and stampede users differ)");
        }
        if (concurrency < 1 || hotSeats < 1 || stormSize < 2) {
            throw new IllegalArgumentException("--concurrency >= 1, --hot-seats >= 1, --storm-size >= 2");
        }
    }

    private static int intOption(Map<String, String> options, String name, int fallback) {
        String value = options.remove(name);
        return value == null ? fallback : Integer.parseInt(value);
    }
}
