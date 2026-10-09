package com.paytmmoney.seatreservation.concurrency;

import static com.paytmmoney.seatreservation.support.TestApi.reservationId;
import static org.assertj.core.api.Assertions.assertThat;

import com.paytmmoney.seatreservation.security.TokenService;
import com.paytmmoney.seatreservation.support.Concurrently;
import com.paytmmoney.seatreservation.support.IntegrationTest;
import com.paytmmoney.seatreservation.support.Invariants;
import com.paytmmoney.seatreservation.support.TestApi;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Multi-seat lock ordering (I1), per-user limit (I5), reconcile during load (I3) and cancel races (I6). */
@IntegrationTest
class CancelAndLimitConcurrencyIT {

    private static final int REPEATS = 5;

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

    /**
     * Classic deadlock bait: [A1,A2], [A2,A3], [A3,A1], each also sent in reverse order. Every pair overlaps every
     * other, so exactly one request can win. With one global lock order there is no deadlock to detect or retry.
     */
    @RepeatedTest(REPEATS)
    void I1_OverlappingMultiSeatInOppositeOrders_NoDeadlockAllOrNothing() {
        int users = 300;
        UUID showId = api.createShow(List.of("A1", "A2", "A3"), 4);
        List<List<String>> shapes = List.of(
                List.of("A1", "A2"),
                List.of("A2", "A1"),
                List.of("A2", "A3"),
                List.of("A3", "A2"),
                List.of("A3", "A1"),
                List.of("A1", "A3"));
        List<String> userTokens = tokensFor("multi", users);

        List<HttpResponse<String>> responses = Concurrently.run(
                users, i -> api.reserve(userTokens.get(i), showId, "k", shapes.get(i % shapes.size())));

        assertNo5xx(responses);
        assertThat(responses.stream().filter(r -> r.statusCode() == 201)).hasSize(1);
        assertThat(responses.stream().filter(r -> r.statusCode() != 201).map(TestApi::errorCode))
                .as("losers are plain seat_taken, never contention")
                .containsOnly("seat_taken");
        assertThat(countSeats(showId, "confirmed")).isEqualTo(2);
        assertThat(api.http().get("/metrics").body())
                .as("no deadlock or serialization retries")
                .doesNotContain("db_tx_retries_total{");
        Invariants.assertHold(jdbc, showId);
        api.assertReconciled(showId);
    }

    @RepeatedTest(REPEATS)
    void I5_TenParallelReserves_LimitFour_ExactlyFour() {
        List<String> labels =
                IntStream.rangeClosed(1, 10).mapToObj(i -> "E" + i).toList();
        UUID showId = api.createShow(labels, 4);
        String token = api.userToken("greedy-" + run);

        List<HttpResponse<String>> responses =
                Concurrently.run(10, i -> api.reserve(token, showId, "g-" + i, List.of(labels.get(i))));

        assertNo5xx(responses);
        assertThat(responses.stream().filter(r -> r.statusCode() == 201)).hasSize(4);
        assertThat(responses.stream().filter(r -> r.statusCode() != 201).map(TestApi::errorCode))
                .hasSize(6)
                .containsOnly("per_user_limit");
        assertThat(userCount(showId, "greedy-" + run)).isEqualTo(4);
        assertThat(countSeats(showId, "confirmed")).isEqualTo(4);
        Invariants.assertHold(jdbc, showId);
    }

    @RepeatedTest(REPEATS)
    void I5_ParallelMultiSeatRequests_NeverExceedLimit() {
        List<String> labels =
                IntStream.rangeClosed(1, 16).mapToObj(i -> "F" + i).toList();
        UUID showId = api.createShow(labels, 4);
        String token = api.userToken("pairs-" + run);

        List<HttpResponse<String>> responses = Concurrently.run(
                8, i -> api.reserve(token, showId, "p-" + i, List.of(labels.get(2 * i), labels.get(2 * i + 1))));

        assertNo5xx(responses);
        assertThat(responses.stream().filter(r -> r.statusCode() == 201)).hasSize(2);
        assertThat(userCount(showId, "pairs-" + run)).isEqualTo(4);
        Invariants.assertHold(jdbc, showId);
    }

    /** I3 must hold at every instant, not just at the end: sample reconcile while the storm is in flight. */
    @RepeatedTest(REPEATS)
    void I3_ReconcileHoldsDuringConcurrentLoad() {
        int seatCount = 40;
        int requests = 240;
        int polls = 60;
        List<String> labels =
                IntStream.rangeClosed(1, seatCount).mapToObj(i -> "G" + i).toList();
        UUID showId = api.createShow(labels, 4);
        List<String> userTokens = tokensFor("load", requests);

        List<HttpResponse<String>> responses = Concurrently.run(requests + polls, i -> {
            if (i >= requests) {
                return api.http().get("/shows/" + showId + "/reconcile");
            }
            String first = labels.get(i % seatCount);
            String second = labels.get((i * 7 + 3) % seatCount);
            List<String> seats = first.equals(second) ? List.of(first) : List.of(first, second);
            return api.reserve(userTokens.get(i), showId, "l", seats);
        });

        assertNo5xx(responses);
        List<HttpResponse<String>> samples = responses.subList(requests, requests + polls);
        assertThat(samples).allSatisfy(sample -> {
            assertThat(sample.statusCode()).isEqualTo(200);
            assertThat(TestApi.json(sample).path("ok").asBoolean())
                    .as(sample.body())
                    .isTrue();
        });
        Invariants.assertHold(jdbc, showId);
        api.assertReconciled(showId);
    }

    /** A cancel racing 50 buyers: the seat ends with at most one new owner, never resurrected or shared. */
    @RepeatedTest(REPEATS)
    void I6_CancelRacingReserve_NoResurrectionOrDoubleAssignment() {
        int buyers = 50;
        UUID showId = api.createShow(List.of("H1", "H2"), 4);
        String owner = api.userToken("owner-" + run);
        String reservation = reservationId(api.reserve(owner, showId, "o", List.of("H1")));
        List<String> buyerTokens = tokensFor("buyer", buyers);

        List<HttpResponse<String>> responses = Concurrently.run(
                buyers + 1,
                i -> i == buyers
                        ? api.cancel(owner, reservation)
                        : api.reserve(buyerTokens.get(i), showId, "b", List.of("H1")));

        assertNo5xx(responses);
        assertThat(responses.get(buyers).statusCode()).as("the cancel").isEqualTo(200);
        List<HttpResponse<String>> buys = responses.subList(0, buyers);
        long winners = buys.stream().filter(r -> r.statusCode() == 201).count();
        assertThat(winners).isLessThanOrEqualTo(1);
        assertThat(buys.stream().filter(r -> r.statusCode() != 201).map(TestApi::errorCode))
                .containsOnly("seat_taken");
        String seatOwner = jdbc.sql("SELECT user_id FROM seats WHERE show_id = ? AND seat_label = 'H1'")
                .param(showId)
                .query(String.class)
                .optional()
                .orElse(null);
        if (winners == 1) {
            assertThat(seatOwner).startsWith("buyer-");
        } else {
            assertThat(seatOwner).isNull();
        }
        Invariants.assertHold(jdbc, showId);
        api.assertReconciled(showId);
    }

    @RepeatedTest(REPEATS)
    void I6_ConcurrentCancelsOfOneReservation_ReleaseExactlyOnce() {
        UUID showId = api.createShow(List.of("J1", "J2"), 4);
        String owner = api.userToken("canceller-" + run);
        String reservation = reservationId(api.reserve(owner, showId, "c", List.of("J1", "J2")));

        List<HttpResponse<String>> responses = Concurrently.run(20, i -> api.cancel(owner, reservation));

        assertNo5xx(responses);
        assertThat(responses).allSatisfy(r -> assertThat(r.statusCode()).isEqualTo(200));
        assertThat(userCount(showId, "canceller-" + run)).isZero();
        assertThat(countSeats(showId, "available")).isEqualTo(2);
        Invariants.assertHold(jdbc, showId);
    }

    private List<String> tokensFor(String prefix, int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> api.userToken(prefix + "-" + run + "-" + i))
                .toList();
    }

    private static void assertNo5xx(List<HttpResponse<String>> responses) {
        assertThat(responses)
                .as("I2: zero 5xx")
                .noneSatisfy(r -> assertThat(r.statusCode()).isGreaterThanOrEqualTo(500));
        assertThat(responses.stream().filter(r -> r.statusCode() == 409).map(TestApi::errorCode))
                .doesNotContain("contention");
    }

    private int countSeats(UUID showId, String status) {
        return jdbc.sql("SELECT count(*) FROM seats WHERE show_id = ? AND status = ?")
                .params(showId, status)
                .query(Integer.class)
                .single();
    }

    private int userCount(UUID showId, String userId) {
        return jdbc.sql("SELECT seat_count FROM user_show_counts WHERE show_id = ? AND user_id = ?")
                .params(showId, userId)
                .query(Integer.class)
                .single();
    }
}
