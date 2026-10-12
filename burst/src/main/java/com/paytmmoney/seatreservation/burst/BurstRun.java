package com.paytmmoney.seatreservation.burst;

import com.paytmmoney.seatreservation.burst.Recorded.Kind;
import com.paytmmoney.seatreservation.burst.Recorded.Scenario;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntConsumer;
import java.util.stream.IntStream;
import tools.jackson.databind.JsonNode;

/** Sets up a fresh show, fires every scenario concurrently, samples invariants throughout, then collects results. */
final class BurstRun {

    static final int IDEMPOTENT_DUPLICATES = 50;
    static final int KEY_REUSE_CONTENDERS = 20;
    static final int GREEDY_REQUESTS = 10;
    /** Spoof (1) plus cancel-and-rebook (2 reserves). */
    static final int SINGLE_SCRIPTED_REQUESTS = 3;

    static final long PRICE_PAISE = 25_000;
    private static final long RANDOM_SEED = 20_261_012L;
    private static final Duration POLL_INTERVAL = Duration.ofMillis(200);
    private static final int SAMPLER_CONCURRENCY = 2;

    private final BurstConfig config;
    private final Api api;
    /** Separate client for invariant sampling, so samples are not queued behind the load's in-flight cap. */
    private final Api sampler;

    private final String run = UUID.randomUUID().toString().substring(0, 8);
    private final Queue<Recorded> recorded = new ConcurrentLinkedQueue<>();
    private final Queue<InvariantSample> samples = new ConcurrentLinkedQueue<>();

    BurstRun(BurstConfig config, Api api) {
        this.config = config;
        this.api = api;
        this.sampler = new Api(config.baseUrl(), SAMPLER_CONCURRENCY);
    }

    /** {@code reachable=false}: the service could not be reached (e.g. mid-restart); not evidence either way. */
    record InvariantSample(String source, boolean reachable, boolean ok, String detail) {}

    record Outcome(
            String run,
            String showId,
            List<String> hotSeats,
            List<Recorded> recorded,
            List<InvariantSample> samples,
            JsonNode finalShow,
            JsonNode finalReconcile,
            String metricsBefore,
            String metricsAfter,
            Duration elapsed,
            long connectRetries) {}

    Outcome execute() {
        requireReady();
        List<String> labels = Seats.labels(config.seats());
        List<String> hotSeats = labels.subList(0, config.hotSeats());
        List<String> scenarioSeats = labels.subList(labels.size() - BurstConfig.SCENARIO_SEATS, labels.size());
        List<String> stampedeSeats = labels.subList(config.hotSeats(), labels.size() - BurstConfig.SCENARIO_SEATS);

        String showId = createShow(adminToken(), labels);
        List<String> tokens = mintUserTokens();
        ScenarioUsers scenarioUsers = new ScenarioUsers(
                token("idem"), token("reuse"), token("greedy"), token("spoof"), token("canceller"), token("rebook"));
        System.out.printf("Show %s: %d seats, %d users, run %s%n", showId, labels.size(), tokens.size(), run);

        String metricsBefore = api.scrapeMetrics();
        long start = System.nanoTime();
        AtomicBoolean running = new AtomicBoolean(true);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> poller = executor.submit(() -> poll(showId, running));
            List<Runnable> work = new ArrayList<>();
            work.addAll(stormTasks(showId, hotSeats, tokens));
            int scripted = IDEMPOTENT_DUPLICATES + KEY_REUSE_CONTENDERS + GREEDY_REQUESTS + SINGLE_SCRIPTED_REQUESTS;
            int stampedeCount = Math.max(0, config.total() - work.size() - scripted);
            work.addAll(stampedeTasks(showId, stampedeSeats, tokens, stampedeCount));
            Collections.shuffle(work, new Random(RANDOM_SEED));

            List<Future<?>> futures = new ArrayList<>();
            futures.add(executor.submit(() -> idempotentRetries(executor, showId, scenarioSeats, scenarioUsers)));
            futures.add(executor.submit(() -> keyReuse(executor, showId, scenarioSeats, scenarioUsers)));
            futures.add(executor.submit(() -> perUserLimit(executor, showId, scenarioSeats, scenarioUsers)));
            futures.add(executor.submit(() -> spoof(showId, scenarioSeats, scenarioUsers)));
            futures.add(executor.submit(() -> cancelAndRebook(showId, scenarioSeats, scenarioUsers)));
            work.forEach(task -> futures.add(executor.submit(task)));
            futures.forEach(BurstRun::join);
            running.set(false);
            join(poller);
        }
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        JsonNode finalShow = api.get("/shows/" + showId, null).body();
        JsonNode finalReconcile =
                api.get("/shows/" + showId + "/reconcile", null).body();
        sampleOnce(showId);
        return new Outcome(
                run,
                showId,
                hotSeats,
                List.copyOf(recorded),
                List.copyOf(samples),
                finalShow,
                finalReconcile,
                metricsBefore,
                api.scrapeMetrics(),
                elapsed,
                api.connectRetries());
    }

    // ---- setup -------------------------------------------------------------------------------------------------

    private void requireReady() {
        Api.Response ready = api.get("/readyz", null);
        if (ready.status() != Http.OK) {
            throw new IllegalStateException(
                    "Service not ready: GET /readyz -> " + ready.status() + " " + Api.describe(ready));
        }
    }

    private String adminToken() {
        Api.Response response = api.post(
                "/auth/token",
                null,
                Map.of("X-Admin-Key", config.adminKey()),
                Map.of("user_id", "burst-admin", "role", "admin"));
        if (response.status() != Http.OK) {
            throw new IllegalStateException("Admin token failed: " + response.status() + " " + Api.describe(response));
        }
        return response.body().path("token").asString();
    }

    private String createShow(String adminToken, List<String> labels) {
        Map<String, Object> show = Map.of(
                "name",
                "Burst " + run,
                "price_paise",
                PRICE_PAISE,
                "per_user_limit",
                config.perUserLimit(),
                "seats",
                labels);
        Api.Response response = api.post("/shows", adminToken, Map.of(), show);
        if (response.status() != Http.CREATED) {
            throw new IllegalStateException("Create show failed: " + response.status() + " " + Api.describe(response));
        }
        return response.body().path("id").asString();
    }

    private List<String> mintUserTokens() {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<String>> futures = IntStream.range(0, config.users())
                    .mapToObj(i -> executor.submit(() -> token("u" + i)))
                    .toList();
            return futures.stream().map(BurstRun::join).toList();
        }
    }

    private String token(String suffix) {
        String userId = "b" + run + "-" + suffix;
        Api.Response response = api.post("/auth/token", null, Map.of(), Map.of("user_id", userId));
        if (response.status() != Http.OK) {
            throw new IllegalStateException("Token for " + userId + " failed: " + response.status());
        }
        return response.body().path("token").asString();
    }

    // ---- load ----------------------------------------------------------------------------------------------------

    /** Users [0, storm-size) hit every hot seat; stampede users are disjoint, so storms can't starve on quota. */
    private List<Runnable> stormTasks(String showId, List<String> hotSeats, List<String> tokens) {
        List<Runnable> tasks = new ArrayList<>();
        for (int h = 0; h < hotSeats.size(); h++) {
            String seat = hotSeats.get(h);
            for (int u = 0; u < config.stormSize(); u++) {
                String token = tokens.get(u);
                String key = "storm-" + h + "-" + u;
                tasks.add(() -> record(
                        Scenario.HOT_SEAT_STORM,
                        List.of(seat),
                        seat,
                        api.reserve(token, showId, key, List.of(seat), Map.of())));
            }
        }
        return tasks;
    }

    private List<Runnable> stampedeTasks(String showId, List<String> seats, List<String> tokens, int count) {
        Random random = new Random(RANDOM_SEED);
        int firstStampedeUser = config.stormSize();
        List<Runnable> tasks = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String token = tokens.get(firstStampedeUser + random.nextInt(tokens.size() - firstStampedeUser));
            int size = 1 + random.nextInt(config.perUserLimit());
            List<String> chosen = new ArrayList<>(size);
            while (chosen.size() < size) {
                String seat = seats.get(random.nextInt(seats.size()));
                if (!chosen.contains(seat)) {
                    chosen.add(seat);
                }
            }
            String key = "stampede-" + i;
            tasks.add(() -> record(Scenario.STAMPEDE, chosen, null, api.reserve(token, showId, key, chosen, Map.of())));
        }
        return tasks;
    }

    // ---- scripted scenarios ---------------------------------------------------------------------------------------

    private record ScenarioUsers(
            String idempotent, String reuse, String greedy, String spoof, String canceller, String rebooker) {}

    /** The same request, same key, fired 50 times at once: one reservation, 49 replays of it. */
    private void idempotentRetries(ExecutorService executor, String showId, List<String> seats, ScenarioUsers users) {
        List<String> seat = List.of(seats.get(0));
        fanOut(
                executor,
                IDEMPOTENT_DUPLICATES,
                i -> record(
                        Scenario.IDEMPOTENT_RETRY,
                        seat,
                        null,
                        api.reserve(users.idempotent(), showId, "same-key", seat, Map.of())));
    }

    /** One key, 20 different seat requests at once: exactly one wins, the rest are key reuse. */
    private void keyReuse(ExecutorService executor, String showId, List<String> seats, ScenarioUsers users) {
        fanOut(executor, KEY_REUSE_CONTENDERS, i -> {
            List<String> seat = List.of(seats.get(1 + i));
            record(Scenario.KEY_REUSE, seat, null, api.reserve(users.reuse(), showId, "shared-key", seat, Map.of()));
        });
    }

    /** One user, 10 parallel single-seat reserves against a limit of 4. */
    private void perUserLimit(ExecutorService executor, String showId, List<String> seats, ScenarioUsers users) {
        int offset = 1 + KEY_REUSE_CONTENDERS;
        fanOut(executor, GREEDY_REQUESTS, i -> {
            List<String> seat = List.of(seats.get(offset + i));
            record(Scenario.PER_USER_LIMIT, seat, null, api.reserve(users.greedy(), showId, "g" + i, seat, Map.of()));
        });
    }

    /** A body naming another user must act as the token's user only. */
    private void spoof(String showId, List<String> seats, ScenarioUsers users) {
        List<String> seat = List.of(seats.get(1 + KEY_REUSE_CONTENDERS + GREEDY_REQUESTS));
        record(
                Scenario.SPOOF,
                seat,
                null,
                api.reserve(users.spoof(), showId, "spoof", seat, Map.of("user_id", "victim")));
    }

    /** Reserve, cancel, and let someone else book the freed seat with their own key. */
    private void cancelAndRebook(String showId, List<String> seats, ScenarioUsers users) {
        List<String> seat = List.of(seats.get(2 + KEY_REUSE_CONTENDERS + GREEDY_REQUESTS));
        Api.Response booked = api.reserve(users.canceller(), showId, "first", seat, Map.of());
        record(Scenario.CANCEL_REBOOK, seat, null, booked);
        if (booked.status() == Http.CREATED) {
            recorded.add(new Recorded(
                    Scenario.CANCEL_REBOOK,
                    Kind.CANCEL,
                    seat,
                    null,
                    api.cancel(users.canceller(), booked.reservationId())));
        }
        record(Scenario.CANCEL_REBOOK, seat, null, api.reserve(users.rebooker(), showId, "second", seat, Map.of()));
    }

    // ---- invariant sampling -----------------------------------------------------------------------------------

    private void poll(String showId, AtomicBoolean running) {
        while (running.get()) {
            sampleOnce(showId);
            try {
                Thread.sleep(POLL_INTERVAL);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** I3 during the burst: counts add up in GET /shows/{id}, and every reconcile check passes. */
    private void sampleOnce(String showId) {
        Api.Response show = sampler.get("/shows/" + showId, null);
        if (show.status() == Http.OK) {
            JsonNode c = show.body().path("counts");
            int sum = c.path("available").asInt()
                    + c.path("held").asInt()
                    + c.path("confirmed").asInt();
            boolean ok = sum == c.path("total").asInt() && c.path("total").asInt() == config.seats();
            samples.add(new InvariantSample("show", true, ok, c.toString()));
        } else {
            samples.add(new InvariantSample("show", !show.isTransportError(), false, "GET /shows -> " + show.status()));
        }
        Api.Response reconcile = sampler.get("/shows/" + showId + "/reconcile", null);
        boolean ok =
                reconcile.status() == Http.OK && reconcile.body().path("ok").asBoolean();
        samples.add(
                new InvariantSample("reconcile", !reconcile.isTransportError(), ok, ok ? "" : Api.describe(reconcile)));
    }

    // ---- plumbing ---------------------------------------------------------------------------------------------

    private void record(Scenario scenario, List<String> seats, String hotSeat, Api.Response response) {
        recorded.add(new Recorded(scenario, Kind.RESERVE, seats, hotSeat, response));
    }

    private static void fanOut(ExecutorService executor, int count, IntConsumer task) {
        List<Future<?>> futures = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int index = i;
            futures.add(executor.submit(() -> task.accept(index)));
        }
        futures.forEach(BurstRun::join);
    }

    private static <T> T join(Future<T> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Burst task failed", e.getCause());
        }
    }
}
