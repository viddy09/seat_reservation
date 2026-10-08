package com.paytmmoney.seatreservation.concurrency;

import static com.paytmmoney.seatreservation.support.TestApi.json;
import static org.assertj.core.api.Assertions.assertThat;

import com.paytmmoney.seatreservation.security.TokenService;
import com.paytmmoney.seatreservation.support.Concurrently;
import com.paytmmoney.seatreservation.support.IntegrationTest;
import com.paytmmoney.seatreservation.support.Invariants;
import com.paytmmoney.seatreservation.support.TestApi;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Invariants I1, I2 and I4 under real concurrency against real Postgres. Each test is repeated to flush out races. */
@IntegrationTest
class ReserveConcurrencyIT {

    private static final int REPEATS = 5;

    @LocalServerPort
    private int port;

    @Autowired
    private TokenService tokens;

    @Autowired
    private JdbcClient jdbc;

    private TestApi api;

    /**
     * Fresh user ids per repetition. Keys are scoped per user, not per show, so reusing a (user, key) pair on a new
     * show is correctly a 409 idempotency_key_reused, which would mask what each test is proving.
     */
    private String run;

    @BeforeEach
    void setUp() {
        api = new TestApi(port, tokens);
        run = UUID.randomUUID().toString().substring(0, 8);
    }

    @RepeatedTest(REPEATS)
    void I1_HotSeatStorm_ExactlyOneWinner() {
        int users = 500;
        UUID showId = api.createShow(List.of("A1", "A2", "A3"), 4);
        List<String> userTokens = IntStream.range(0, users)
                .mapToObj(i -> api.userToken("storm-" + run + "-" + i))
                .toList();

        List<HttpResponse<String>> responses =
                Concurrently.run(users, i -> api.reserve(userTokens.get(i), showId, "k-" + i, List.of("A2")));

        Map<Integer, Long> byStatus = countBy(responses, HttpResponse::statusCode);
        assertNo5xx(responses);
        assertThat(byStatus)
                .containsEntry(201, 1L)
                .containsEntry(409, (long) users - 1)
                .hasSize(2);
        assertThat(responses.stream().filter(r -> r.statusCode() == 409).map(TestApi::errorCode))
                .containsOnly("seat_taken");
        assertThat(confirmedSeats(showId)).containsExactly("A2");
        Invariants.assertHold(jdbc, showId);
    }

    @RepeatedTest(REPEATS)
    void I4_SameKeyFiftyTimes_OneReservationRestReplays() {
        int duplicates = 50;
        UUID showId = api.createShow(List.of("B1", "B2"), 4);
        String token = api.userToken("retry-user-" + UUID.randomUUID());

        List<HttpResponse<String>> responses =
                Concurrently.run(duplicates, i -> api.reserve(token, showId, "same-key", List.of("B1")));

        assertNo5xx(responses);
        Map<Integer, Long> byStatus = countBy(responses, HttpResponse::statusCode);
        assertThat(byStatus)
                .containsEntry(201, 1L)
                .containsEntry(200, (long) duplicates - 1)
                .hasSize(2);
        assertThat(responses.stream()
                        .filter(r -> r.statusCode() == 200)
                        .map(r -> r.headers().firstValue("Idempotent-Replayed").orElse("")))
                .containsOnly("true");
        assertThat(responses.stream().map(r -> json(r).path("reservation_id").asString()))
                .as("every response carries the one reservation")
                .containsOnly(json(responses.getFirst()).path("reservation_id").asString());
        assertThat(reservationCount(showId)).isEqualTo(1);
        Invariants.assertHold(jdbc, showId);
    }

    @RepeatedTest(REPEATS)
    void I4_SameKeyDifferentSeats_ExactlyOneWinsRestRejected() {
        int contenders = 20;
        List<String> labels =
                IntStream.range(1, contenders + 1).mapToObj(i -> "C" + i).toList();
        UUID showId = api.createShow(labels, 4);
        String token = api.userToken("reuse-user-" + UUID.randomUUID());

        List<HttpResponse<String>> responses =
                Concurrently.run(contenders, i -> api.reserve(token, showId, "shared-key", List.of(labels.get(i))));

        assertNo5xx(responses);
        assertThat(responses.stream().filter(r -> r.statusCode() == 201)).hasSize(1);
        // Every other request carried a different body, so none of them can be a replay.
        assertThat(responses.stream().filter(r -> r.statusCode() != 201).map(TestApi::errorCode))
                .hasSize(contenders - 1)
                .containsOnly("idempotency_key_reused");
        assertThat(reservationCount(showId)).isEqualTo(1);
        assertThat(confirmedSeats(showId)).hasSize(1);
        Invariants.assertHold(jdbc, showId);
    }

    @RepeatedTest(REPEATS)
    void I6_SpoofedUserIdInBodyIsIgnoredUnderConcurrency() {
        int users = 30;
        List<String> labels =
                IntStream.range(1, users + 1).mapToObj(i -> "D" + i).toList();
        UUID showId = api.createShow(labels, 4);

        List<HttpResponse<String>> responses = Concurrently.run(users, i -> {
            String body = TestApi.JSON.writeValueAsString(
                    Map.of("seats", List.of(labels.get(i)), "user_id", "victim", "idempotency_key", "s-" + i));
            return api.http()
                    .post(
                            "/shows/" + showId + "/reserve",
                            body,
                            TestApi.bearer(api.userToken("real-" + run + "-" + i)));
        });

        assertNo5xx(responses);
        assertThat(responses).allSatisfy(r -> assertThat(r.statusCode()).isEqualTo(201));
        assertThat(jdbc.sql("SELECT count(*) FROM reservations WHERE show_id = ? AND user_id = 'victim'")
                        .param(showId)
                        .query(Integer.class)
                        .single())
                .isZero();
        Invariants.assertHold(jdbc, showId);
    }

    private static void assertNo5xx(List<HttpResponse<String>> responses) {
        assertThat(responses)
                .as("I2: zero 5xx")
                .noneSatisfy(r -> assertThat(r.statusCode()).isGreaterThanOrEqualTo(500));
    }

    private static <K> Map<K, Long> countBy(
            List<HttpResponse<String>> responses, Function<HttpResponse<String>, K> key) {
        return responses.stream().collect(Collectors.groupingBy(key, Collectors.counting()));
    }

    private List<String> confirmedSeats(UUID showId) {
        return jdbc.sql("SELECT seat_label FROM seats WHERE show_id = ? AND status = 'confirmed' ORDER BY 1")
                .param(showId)
                .query(String.class)
                .list();
    }

    private int reservationCount(UUID showId) {
        return jdbc.sql("SELECT count(*) FROM reservations WHERE show_id = ?")
                .param(showId)
                .query(Integer.class)
                .single();
    }
}
