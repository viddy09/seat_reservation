package com.paytmmoney.seatreservation.burst;

import com.paytmmoney.seatreservation.burst.Recorded.Kind;
import com.paytmmoney.seatreservation.burst.Recorded.Scenario;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;

/** Turns a finished run into the printed report and the list of pass/fail checks that decide the exit code. */
final class Report {

    private static final List<String> DECLINE_REASONS = List.of(
            "seat_taken",
            "per_user_limit",
            "idempotent_replay",
            "idempotency_key_reused",
            "validation",
            "unauthorized",
            "not_found",
            "contention",
            "server_busy");
    private static final int[] PERCENTILES = {50, 95, 99};
    private static final long NANOS_PER_MILLI = 1_000_000;
    private static final int PERCENT = 100;
    private static final int SERVER_ERROR = 500;
    private static final int CLIENT_ERROR = 400;
    private static final int MAX_FAILED_SAMPLES_SHOWN = 3;

    record Check(String name, boolean ok, String detail) {}

    private final BurstRun.Outcome outcome;
    private final List<Recorded> reserves;
    private final List<Recorded> cancels;
    private final List<Check> checks = new ArrayList<>();

    Report(BurstRun.Outcome outcome) {
        this.outcome = outcome;
        this.reserves = outcome.recorded().stream()
                .filter(r -> r.kind() == Kind.RESERVE)
                .toList();
        this.cancels =
                outcome.recorded().stream().filter(r -> r.kind() == Kind.CANCEL).toList();
        evaluate();
    }

    boolean passed() {
        return checks.stream().allMatch(Check::ok);
    }

    // ---- checks -----------------------------------------------------------------------------------------------

    private void evaluate() {
        long serverErrors = outcome.recorded().stream()
                .filter(r -> r.response().status() >= SERVER_ERROR)
                .count();
        long transportErrors = outcome.recorded().stream()
                .filter(r -> r.response().isTransportError())
                .count();
        check("zero 5xx", serverErrors == 0, serverErrors + " responses >= 500");
        check("zero transport errors", transportErrors == 0, transportErrors + " requests failed below HTTP");

        for (String seat : outcome.hotSeats()) {
            long winners = count(r -> seat.equals(r.hotSeat()) && r.response().status() == Http.CREATED);
            check("hot seat " + seat + " has exactly one winner", winners == 1, winners + " winners");
        }

        long failedSamples = outcome.samples().stream().filter(s -> !s.ok()).count();
        check(
                "invariant held at every sample",
                failedSamples == 0 && !outcome.samples().isEmpty(),
                outcome.samples().size() + " samples, " + failedSamples + " failed" + firstFailures());
        check("final reconcile ok", outcome.finalReconcile().path("ok").asBoolean(), failingReconcileChecks());

        long expectedConfirmedSeats = reserves.stream()
                        .filter(r -> r.response().status() == Http.CREATED)
                        .mapToLong(r -> r.seats().size())
                        .sum()
                - cancels.stream()
                        .filter(r -> r.response().status() == Http.OK)
                        .mapToLong(r -> r.seats().size())
                        .sum();
        long confirmedSeats =
                outcome.finalShow().path("counts").path("confirmed").asLong();
        check(
                "confirmed seats == seats in 201s minus cancelled",
                confirmedSeats == expectedConfirmedSeats,
                confirmedSeats + " vs " + expectedConfirmedSeats);

        checkIdempotency();
        checkScenarios();
        checkMetrics();
    }

    private void checkIdempotency() {
        List<Recorded> retries = scenario(Scenario.IDEMPOTENT_RETRY);
        long created = retries.stream()
                .filter(r -> r.response().status() == Http.CREATED)
                .count();
        long replays = retries.stream().filter(r -> r.response().replayed()).count();
        long distinctIds = retries.stream()
                .map(r -> r.response().reservationId())
                .filter(id -> !id.isEmpty())
                .distinct()
                .count();
        check(
                "same key x" + retries.size() + ": one reservation, rest replays",
                created == 1 && replays == retries.size() - 1 && distinctIds == 1,
                created + " created, " + replays + " replays, " + distinctIds + " distinct reservation ids");

        List<Recorded> reuse = scenario(Scenario.KEY_REUSE);
        long reuseWins = reuse.stream()
                .filter(r -> r.response().status() == Http.CREATED)
                .count();
        long reused = reuse.stream()
                .filter(r -> "idempotency_key_reused".equals(r.response().errorCode()))
                .count();
        check(
                "same key, different seats: one winner, rest key_reused",
                reuseWins == 1 && reused == reuse.size() - 1,
                reuseWins + " created, " + reused + " key_reused");
    }

    private void checkScenarios() {
        List<Recorded> greedy = scenario(Scenario.PER_USER_LIMIT);
        long granted = greedy.stream()
                .filter(r -> r.response().status() == Http.CREATED)
                .count();
        check(
                "one user, " + greedy.size() + " parallel reserves: at most 4 seats",
                granted == BurstConfig.PER_USER_LIMIT,
                granted + " granted");

        List<Recorded> spoof = scenario(Scenario.SPOOF);
        String spoofUser = "b" + outcome.run() + "-spoof";
        boolean spoofIgnored = spoof.size() == 1
                && spoof.getFirst().response().status() == Http.CREATED
                && spoofUser.equals(
                        spoof.getFirst().response().body().path("user_id").asString());
        check("spoofed body user_id ignored", spoofIgnored, spoof.isEmpty() ? "not run" : describe(spoof.getFirst()));

        List<Recorded> rebook = scenario(Scenario.CANCEL_REBOOK);
        boolean cancelOk = cancels.size() == 1
                && cancels.getFirst().response().status() == Http.OK
                && "cancelled"
                        .equals(cancels.getFirst()
                                .response()
                                .body()
                                .path("status")
                                .asString());
        boolean rebooked = rebook.size() == 2
                && rebook.get(0).response().status() == Http.CREATED
                && rebook.get(1).response().status() == Http.CREATED;
        check(
                "cancel then re-book by another user",
                cancelOk && rebooked,
                rebook.size() + " reserves, cancel " + cancelOk);
    }

    /** In-process counters must match what clients observed (single instance, no other traffic during the run). */
    private void checkMetrics() {
        String before = outcome.metricsBefore();
        String after = outcome.metricsAfter();
        long confirmedDelta = delta(before, after, "seatres_reservations_confirmed_total", Map.of());
        long created = count(r -> r.response().status() == Http.CREATED);
        check("metric confirmed == observed 201s", confirmedDelta == created, confirmedDelta + " vs " + created);

        long cancelledDelta = delta(before, after, "seatres_reservations_cancelled_total", Map.of());
        long cancelled =
                cancels.stream().filter(r -> r.response().status() == Http.OK).count();
        check("metric cancelled == observed cancels", cancelledDelta == cancelled, cancelledDelta + " vs " + cancelled);

        Map<String, Long> observed = declinesByReason();
        List<String> mismatches = new ArrayList<>();
        for (String reason : DECLINE_REASONS) {
            long metric = delta(before, after, "seatres_reservations_declined_total", Map.of("reason", reason));
            long seen = observed.getOrDefault(reason, 0L);
            if (metric != seen) {
                mismatches.add(reason + " " + metric + " vs " + seen);
            }
        }
        check("metric declined{reason} == observed", mismatches.isEmpty(), String.join(", ", mismatches));
    }

    // ---- printing ---------------------------------------------------------------------------------------------

    void print(PrintStream out) {
        out.println();
        out.printf(
                "Burst finished in %d ms: %d reserve requests, %d cancels%n",
                outcome.elapsed().toMillis(), reserves.size(), cancels.size());
        out.println();
        out.println("Outcome distribution (reserve requests)");
        printRow(out, "201 created", count(r -> r.response().status() == Http.CREATED));
        printRow(out, "200 idempotent replay", count(r -> r.response().replayed()));
        declinesByReason().forEach((reason, n) -> {
            if (!"idempotent_replay".equals(reason)) {
                printRow(out, "4xx " + reason, n);
            }
        });
        printRow(out, "5xx", count(r -> r.response().status() >= SERVER_ERROR));
        printRow(out, "transport errors", count(r -> r.response().isTransportError()));
        printRow(out, "client connect retries (never sent)", outcome.connectRetries());
        outcome.recorded().stream()
                .filter(r -> r.response().isTransportError())
                .collect(Collectors.groupingBy(r -> r.response().transportError(), TreeMap::new, Collectors.counting()))
                .forEach((error, n) -> out.printf("    %6d x %s%n", n, error));
        out.println();

        long[] latencies = reserves.stream()
                .filter(r -> !r.response().isTransportError())
                .mapToLong(r -> r.response().latencyNanos())
                .sorted()
                .toArray();
        out.print("Latency (ms):");
        for (int p : PERCENTILES) {
            out.printf("  p%d=%d", p, percentile(latencies, p) / NANOS_PER_MILLI);
        }
        out.printf("  max=%d%n%n", latencies.length == 0 ? 0 : latencies[latencies.length - 1] / NANOS_PER_MILLI);

        out.println("Hot seats (winners / contenders)");
        for (String seat : outcome.hotSeats()) {
            long contenders = count(r -> seat.equals(r.hotSeat()));
            long winners = count(r -> seat.equals(r.hotSeat()) && r.response().status() == Http.CREATED);
            out.printf("  %-6s %d / %d%n", seat, winners, contenders);
        }
        out.println();
        out.println("Final show counts: " + outcome.finalShow().path("counts"));
        out.println("Final reconcile ok: " + outcome.finalReconcile().path("ok").asBoolean());
        out.println();
        out.println("Checks");
        for (Check check : checks) {
            out.printf(
                    "  [%s] %s%s%n",
                    check.ok() ? "PASS" : "FAIL",
                    check.name(),
                    check.detail().isEmpty() ? "" : " (" + check.detail() + ")");
        }
        out.println();
        out.println(passed() ? "RESULT: PASS" : "RESULT: FAIL");
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    private Map<String, Long> declinesByReason() {
        return reserves.stream()
                .filter(r -> r.response().status() == Http.OK
                        || (r.response().status() >= CLIENT_ERROR
                                && r.response().status() < SERVER_ERROR))
                .collect(Collectors.groupingBy(Recorded::declineReason, TreeMap::new, Collectors.counting()));
    }

    private List<Recorded> scenario(Scenario scenario) {
        return reserves.stream().filter(r -> r.scenario() == scenario).toList();
    }

    private long count(Predicate<Recorded> predicate) {
        return reserves.stream().filter(predicate).count();
    }

    private void check(String name, boolean ok, String detail) {
        checks.add(new Check(name, ok, detail));
    }

    private String firstFailures() {
        List<String> failures = outcome.samples().stream()
                .filter(s -> !s.ok())
                .limit(MAX_FAILED_SAMPLES_SHOWN)
                .map(s -> s.source() + ": " + s.detail())
                .toList();
        return failures.isEmpty() ? "" : "; " + String.join(" | ", failures);
    }

    private String failingReconcileChecks() {
        List<String> failing = new ArrayList<>();
        for (JsonNode check : outcome.finalReconcile().path("checks").values()) {
            if (!check.path("ok").asBoolean()) {
                failing.add(check.path("name").asString() + ": "
                        + check.path("detail").asString());
            }
        }
        return String.join("; ", failing);
    }

    private static String describe(Recorded recorded) {
        return recorded.response().status() + " " + Api.describe(recorded.response());
    }

    private static long delta(String before, String after, String metric, Map<String, String> labels) {
        return PrometheusText.value(after, metric, labels) - PrometheusText.value(before, metric, labels);
    }

    /** Nearest-rank percentile over sorted values, in integer arithmetic. */
    static long percentile(long[] sorted, int percentile) {
        if (sorted.length == 0) {
            return 0;
        }
        int rank = (int) ((percentile * (long) sorted.length + PERCENT - 1) / PERCENT);
        return sorted[Math.max(0, Math.min(sorted.length, rank) - 1)];
    }

    private static void printRow(PrintStream out, String label, long count) {
        out.printf("  %-34s %8d%n", label, count);
    }
}
