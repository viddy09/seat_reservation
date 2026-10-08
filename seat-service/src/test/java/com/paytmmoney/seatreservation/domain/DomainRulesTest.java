package com.paytmmoney.seatreservation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DomainRulesTest {

    private static final UUID SHOW = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void fingerprint_isOrderInsensitive() {
        assertThat(RequestFingerprint.of(SHOW, List.of("A13", "A12")))
                .isEqualTo(RequestFingerprint.of(SHOW, List.of("A12", "A13")));
    }

    @Test
    void fingerprint_differsBySeatsAndShow() {
        RequestFingerprint base = RequestFingerprint.of(SHOW, List.of("A1"));
        assertThat(RequestFingerprint.of(SHOW, List.of("A2"))).isNotEqualTo(base);
        assertThat(RequestFingerprint.of(UUID.randomUUID(), List.of("A1"))).isNotEqualTo(base);
        assertThat(base.bytes()).hasSize(32);
    }

    @Test
    void money_multipliesExactly() {
        assertThat(Money.total(25_000, 4)).isEqualTo(100_000);
    }

    @Test
    void money_overflowIsRejectedNotWrapped() {
        assertThatThrownBy(() -> Money.total(Long.MAX_VALUE / 2 + 1, 2)).isInstanceOf(RequestValidationException.class);
    }

    @Test
    void seatSelection_sortsAndValidates() {
        assertThat(SeatSelection.forReservation(List.of("B2", "A10", "A2"))).containsExactly("A10", "A2", "B2");
        assertThatThrownBy(() -> SeatSelection.forReservation(List.of()))
                .isInstanceOf(RequestValidationException.class);
        assertThatThrownBy(() -> SeatSelection.forReservation(null)).isInstanceOf(RequestValidationException.class);
        assertThatThrownBy(() -> SeatSelection.forReservation(List.of("A1", "A1")))
                .isInstanceOf(RequestValidationException.class);
        assertThatThrownBy(() -> SeatSelection.forReservation(Arrays.asList("A1", null)))
                .isInstanceOf(RequestValidationException.class);
        for (String bad : List.of("a1", "A", "1", "ABCD1", "A12345", "A-1", " A1")) {
            assertThatThrownBy(() -> SeatSelection.forReservation(List.of(bad)))
                    .as(bad)
                    .isInstanceOf(RequestValidationException.class);
        }
    }

    @Test
    void idempotencyKey_resolution() {
        assertThat(IdempotencyKey.resolve("h", null)).isEqualTo("h");
        assertThat(IdempotencyKey.resolve(null, "b")).isEqualTo("b");
        assertThat(IdempotencyKey.resolve("same", "same")).isEqualTo("same");
        assertThatThrownBy(() -> IdempotencyKey.resolve(null, null))
                .isInstanceOf(IdempotencyKeyRequiredException.class);
        assertThatThrownBy(() -> IdempotencyKey.resolve("", "")).isInstanceOf(IdempotencyKeyRequiredException.class);
        assertThatThrownBy(() -> IdempotencyKey.resolve("a", "b")).isInstanceOf(IdempotencyKeyMismatchException.class);
        assertThatThrownBy(() -> IdempotencyKey.resolve("has space", null))
                .isInstanceOf(RequestValidationException.class);
        assertThatThrownBy(() -> IdempotencyKey.resolve("x".repeat(129), null))
                .isInstanceOf(RequestValidationException.class);
    }

    @Test
    void newShow_appliesDefaultsAndLimits() {
        NewShow show = NewShow.of("Gala", 100L, null, List.of("B1", "A1"));
        assertThat(show.perUserLimit()).isEqualTo(ShowRules.DEFAULT_PER_USER_LIMIT);
        assertThat(show.seats()).containsExactly("A1", "B1");

        assertThatThrownBy(() -> NewShow.of("Gala", 0L, null, List.of("A1")))
                .isInstanceOf(RequestValidationException.class);
        assertThatThrownBy(() -> NewShow.of("Gala", 100L, 0, List.of("A1")))
                .isInstanceOf(RequestValidationException.class);
        assertThatThrownBy(() -> NewShow.of(" ", 100L, null, List.of("A1")))
                .isInstanceOf(RequestValidationException.class);
        assertThatThrownBy(() -> NewShow.of("Gala", Long.MAX_VALUE, 4, List.of("A1", "A2")))
                .as("the largest possible reservation must not overflow")
                .isInstanceOf(RequestValidationException.class);
    }

    @Test
    void seatCounts_addUp() {
        SeatCounts counts = SeatCounts.of(List.of(
                new Seat("A1", SeatStatus.AVAILABLE),
                new Seat("A2", SeatStatus.CONFIRMED),
                new Seat("A3", SeatStatus.HELD)));
        assertThat(counts).isEqualTo(new SeatCounts(3, 1, 1, 1));
    }
}
