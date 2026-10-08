package com.paytmmoney.seatreservation.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.paytmmoney.seatreservation.common.SqlStates;
import com.paytmmoney.seatreservation.domain.ContentionException;
import com.paytmmoney.seatreservation.domain.IdempotencyKeyMismatchException;
import com.paytmmoney.seatreservation.domain.IdempotencyKeyRequiredException;
import com.paytmmoney.seatreservation.domain.IdempotencyKeyReusedException;
import com.paytmmoney.seatreservation.domain.InvalidAdminKeyException;
import com.paytmmoney.seatreservation.domain.PerUserLimitException;
import com.paytmmoney.seatreservation.domain.RequestValidationException;
import com.paytmmoney.seatreservation.domain.ReservationNotFoundException;
import com.paytmmoney.seatreservation.domain.SeatTakenException;
import com.paytmmoney.seatreservation.domain.ShowNotFoundException;
import com.paytmmoney.seatreservation.domain.UnknownSeatException;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class DomainErrorMappingTest {

    @Test
    void everyDeclineHasItsStatusAndCode() {
        assertMapped(new ShowNotFoundException(), 404, "not_found");
        assertMapped(new ReservationNotFoundException(), 404, "not_found");
        assertMapped(new SeatTakenException(List.of("A1")), 409, "seat_taken");
        assertMapped(new PerUserLimitException(4), 409, "per_user_limit");
        assertMapped(new IdempotencyKeyReusedException(), 409, "idempotency_key_reused");
        assertMapped(new ContentionException(), 409, "contention");
        assertMapped(new IdempotencyKeyRequiredException(), 400, "idempotency_key_required");
        assertMapped(new IdempotencyKeyMismatchException(), 422, "idempotency_key_mismatch");
        assertMapped(new UnknownSeatException(List.of("Z1")), 422, "unknown_seat");
        assertMapped(new RequestValidationException("seats", "bad"), 422, "validation");
        assertMapped(new InvalidAdminKeyException(), 401, "invalid_admin_key");
    }

    @Test
    void contentionAsksClientToRetry() {
        assertThat(DomainErrorMapping.map(new ContentionException()).retryAfterSeconds())
                .isPositive();
    }

    @Test
    void sqlStatesAreClassifiedFromTheRootCause() {
        assertThat(SqlStates.classify(wrap(new SQLException("deadlock", "40P01"))))
                .isEqualTo(SqlStates.Failure.RETRYABLE);
        assertThat(SqlStates.classify(wrap(new SQLException("serialization", "40001"))))
                .isEqualTo(SqlStates.Failure.RETRYABLE);
        assertThat(SqlStates.classify(wrap(new SQLException("timeout", "57014"))))
                .isEqualTo(SqlStates.Failure.BUSY);
        assertThat(SqlStates.classify(wrap(new SQLTransientConnectionException("pool timeout"))))
                .isEqualTo(SqlStates.Failure.BUSY);
        assertThat(SqlStates.classify(wrap(new SQLTransientConnectionException("down", "08001"))))
                .isEqualTo(SqlStates.Failure.UNAVAILABLE);
        assertThat(SqlStates.classify(wrap(new SQLException("syntax", "42601"))))
                .isEqualTo(SqlStates.Failure.OTHER);
    }

    private static DataAccessResourceFailureException wrap(SQLException root) {
        return new DataAccessResourceFailureException("wrapped", new RuntimeException("middle", root));
    }

    private static void assertMapped(
            com.paytmmoney.seatreservation.domain.DomainException ex, int status, String code) {
        DomainErrorMapping.Mapped mapped = DomainErrorMapping.map(ex);
        assertThat(mapped.status().value()).as(code).isEqualTo(status);
        assertThat(mapped.code()).isEqualTo(code);
    }
}
