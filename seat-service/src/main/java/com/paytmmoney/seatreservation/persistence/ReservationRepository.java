package com.paytmmoney.seatreservation.persistence;

import com.paytmmoney.seatreservation.domain.RequestFingerprint;
import com.paytmmoney.seatreservation.domain.Reservation;
import com.paytmmoney.seatreservation.domain.ReservationStatus;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ReservationRepository {

    private static final String COLUMNS =
            "id, show_id, user_id, idempotency_key, request_hash, seats, amount_paise, status, created_at, updated_at";

    private final JdbcClient jdbc;

    public ReservationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Lock-order step 1: claim the (user, idempotency key) pair by inserting the reservation row.
     *
     * <p>A concurrent insert with the same pair blocks on the unique index until this transaction ends. If this one
     * commits, the other conflicts and becomes a replay; if it rolls back, the other proceeds as a fresh request
     * (ADR-008). Returns the inserted row, or empty when the key was already taken.
     */
    public Optional<Reservation> claim(
            UUID id,
            UUID showId,
            String userId,
            String idempotencyKey,
            RequestFingerprint fingerprint,
            List<String> seats,
            long amountPaise) {
        return jdbc.sql("""
                        INSERT INTO reservations
                            (id, show_id, user_id, idempotency_key, request_hash, seats, amount_paise, status)
                        VALUES (?, ?, ?, ?, ?, ?::text[], ?, 'confirmed')
                        ON CONFLICT (user_id, idempotency_key) DO NOTHING
                        RETURNING\s""" + COLUMNS)
                .params(
                        id,
                        showId,
                        userId,
                        idempotencyKey,
                        fingerprint.bytes(),
                        seats.toArray(String[]::new),
                        amountPaise)
                .query(ReservationRepository::map)
                .optional();
    }

    public Optional<Reservation> findByUserAndKey(String userId, String idempotencyKey) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM reservations WHERE user_id = ? AND idempotency_key = ?")
                .params(userId, idempotencyKey)
                .query(ReservationRepository::map)
                .optional();
    }

    /** Ownership is part of the lookup: another user's reservation is simply "not found" (ADR-016). */
    public Optional<Reservation> findByIdForOwner(UUID id, String userId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM reservations WHERE id = ? AND user_id = ?")
                .params(id, userId)
                .query(ReservationRepository::map)
                .optional();
    }

    /**
     * Cancel lock-order step 1: row-lock the owner's reservation. A concurrent cancel of the same reservation waits
     * here and then sees {@code cancelled}, so the seat count is decremented exactly once.
     */
    public Optional<Reservation> lockForOwner(UUID id, String userId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM reservations WHERE id = ? AND user_id = ? FOR UPDATE")
                .params(id, userId)
                .query(ReservationRepository::map)
                .optional();
    }

    /** Marks a locked, confirmed reservation cancelled and returns its new state. */
    public Reservation markCancelled(UUID id) {
        return jdbc.sql("UPDATE reservations SET status = 'cancelled', updated_at = now()"
                        + " WHERE id = ? AND status = 'confirmed' RETURNING " + COLUMNS)
                .param(id)
                .query(ReservationRepository::map)
                .optional()
                .orElseThrow(() -> new IllegalStateException("Reservation " + id + " was not confirmed under lock"));
    }

    private static Reservation map(ResultSet rs, int row) throws SQLException {
        return new Reservation(
                rs.getObject("id", UUID.class),
                rs.getObject("show_id", UUID.class),
                rs.getString("user_id"),
                rs.getString("idempotency_key"),
                RequestFingerprint.fromBytes(rs.getBytes("request_hash")),
                textArray(rs.getArray("seats")),
                rs.getLong("amount_paise"),
                ReservationStatus.fromDb(rs.getString("status")),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    private static List<String> textArray(Array array) throws SQLException {
        try {
            return List.of((String[]) array.getArray());
        } finally {
            array.free();
        }
    }
}
