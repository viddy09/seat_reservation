package com.paytmmoney.seatreservation.service;

import com.paytmmoney.seatreservation.domain.ContentionException;
import com.paytmmoney.seatreservation.domain.IdempotencyKey;
import com.paytmmoney.seatreservation.domain.IdempotencyKeyReusedException;
import com.paytmmoney.seatreservation.domain.Money;
import com.paytmmoney.seatreservation.domain.PerUserLimitException;
import com.paytmmoney.seatreservation.domain.RequestFingerprint;
import com.paytmmoney.seatreservation.domain.Reservation;
import com.paytmmoney.seatreservation.domain.ReservationNotFoundException;
import com.paytmmoney.seatreservation.domain.ReservationStatus;
import com.paytmmoney.seatreservation.domain.Seat;
import com.paytmmoney.seatreservation.domain.SeatSelection;
import com.paytmmoney.seatreservation.domain.SeatStatus;
import com.paytmmoney.seatreservation.domain.SeatTakenException;
import com.paytmmoney.seatreservation.domain.Show;
import com.paytmmoney.seatreservation.domain.ShowNotFoundException;
import com.paytmmoney.seatreservation.domain.UnknownSeatException;
import com.paytmmoney.seatreservation.persistence.ReservationRepository;
import com.paytmmoney.seatreservation.persistence.ReservePrecheckRepository;
import com.paytmmoney.seatreservation.persistence.ReservePrecheckRepository.Precheck;
import com.paytmmoney.seatreservation.persistence.SeatRepository;
import com.paytmmoney.seatreservation.persistence.TxRetry;
import com.paytmmoney.seatreservation.persistence.UserShowCountRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reserve and view reservations. The reserve transaction is the core of the assignment; see docs/DESIGN.md §6.1.
 *
 * <p>Global lock order, identical in every transaction (ADR-006): (1) reservation / idempotency row, (2)
 * {@code user_show_counts} row, (3) seat rows sorted by label.
 */
@Service
public class ReservationService {

    private static final String RESERVE = "reserve";
    private static final String CANCEL = "cancel";

    private final ReservePrecheckRepository prechecks;
    private final ReservationRepository reservations;
    private final UserShowCountRepository userCounts;
    private final SeatRepository seats;
    private final TransactionTemplate transaction;
    private final TxRetry txRetry;

    public ReservationService(
            ReservePrecheckRepository prechecks,
            ReservationRepository reservations,
            UserShowCountRepository userCounts,
            SeatRepository seats,
            TransactionTemplate transaction,
            TxRetry txRetry) {
        this.prechecks = prechecks;
        this.reservations = reservations;
        this.userCounts = userCounts;
        this.seats = seats;
        this.transaction = transaction;
        this.txRetry = txRetry;
    }

    /** {@code request.userId()} comes from the verified token only, never from the request body (I6). */
    public ReserveOutcome reserve(UserReserveRequest request) {
        // Pre-transaction checks, in the fixed order of ADR-018, from one non-locking read (ADR-040). Show data is
        // immutable (ADR-026), so those checks can't race with anything.
        String key = IdempotencyKey.resolve(request.headerKey(), request.bodyKey());
        List<String> sortedSeats = SeatSelection.forReservation(request.seats());
        Precheck precheck = prechecks
                .find(request.showId(), request.userId(), key, sortedSeats)
                .orElseThrow(ShowNotFoundException::new);
        Show show = precheck.show();
        if (sortedSeats.size() > show.perUserLimit()) {
            throw new PerUserLimitException(show.perUserLimit());
        }
        if (!precheck.missingSeats().isEmpty()) {
            throw new UnknownSeatException(precheck.missingSeats());
        }
        // Hot-path decline: a seat already taken in a committed snapshot. Skipped when this key already has a
        // reservation, so a retry of a successful request still reaches the transaction and replays (200).
        if (!precheck.keyExists() && !precheck.unavailableSeats().isEmpty()) {
            throw new SeatTakenException(precheck.unavailableSeats());
        }

        ReserveAttempt attempt = new ReserveAttempt(
                show,
                request.userId(),
                key,
                sortedSeats,
                RequestFingerprint.of(show.id(), sortedSeats),
                Money.total(show.pricePaise(), sortedSeats.size()));

        // Each retry is a fresh transaction with a fresh reservation id.
        return txRetry.run(
                RESERVE, () -> transaction.execute(status -> reserveOnce(attempt)), ContentionException::new);
    }

    /** One transaction. Any decline throws, which rolls back everything including the key claim (ADR-010). */
    private ReserveOutcome reserveOnce(ReserveAttempt attempt) {
        UUID reservationId = UUID.randomUUID();

        // (1) Claim the idempotency key.
        var claimed = reservations.claim(
                reservationId,
                attempt.show().id(),
                attempt.userId(),
                attempt.key(),
                attempt.fingerprint(),
                attempt.seats(),
                attempt.amountPaise());
        if (claimed.isEmpty()) {
            Reservation existing = reservations
                    .findByUserAndKey(attempt.userId(), attempt.key())
                    .orElseThrow(() -> new IllegalStateException("Key conflict without an existing row"));
            if (!existing.fingerprint().equals(attempt.fingerprint())) {
                throw new IdempotencyKeyReusedException();
            }
            return ReserveOutcome.replayed(existing);
        }

        // (2) Per-user limit: atomic conditional upsert.
        if (!userCounts.tryAdd(
                attempt.show().id(),
                attempt.userId(),
                attempt.seats().size(),
                attempt.show().perUserLimit())) {
            throw new PerUserLimitException(attempt.show().perUserLimit());
        }

        // (3) Lock seats in label order; losers wait here, then see the winner's committed status.
        List<Seat> locked = seats.lockForUpdate(attempt.show().id(), attempt.seats());
        if (locked.size() != attempt.seats().size()) {
            throw new IllegalStateException(
                    "Seat rows vanished for show " + attempt.show().id());
        }
        List<String> taken = locked.stream()
                .filter(seat -> seat.status() != SeatStatus.AVAILABLE)
                .map(Seat::label)
                .toList();
        if (!taken.isEmpty()) {
            throw new SeatTakenException(taken);
        }

        // (4) Conditional write with the row count asserted: defence in depth against a double-sell.
        int confirmed = seats.confirm(attempt.show().id(), attempt.seats(), reservationId, attempt.userId());
        if (confirmed != attempt.seats().size()) {
            throw new IllegalStateException(
                    "Confirmed " + confirmed + " of " + attempt.seats().size() + " locked available seats");
        }
        return ReserveOutcome.created(claimed.get());
    }

    /**
     * Owner-only, idempotent cancel (ADR-016), in the same lock order as reserve: (1) reservation row, (2) user count
     * row, (3) seat rows sorted by label. A non-owner gets 404, exactly like an unknown id.
     */
    public CancelOutcome cancel(UUID reservationId, String userId) {
        return txRetry.run(
                CANCEL,
                () -> transaction.execute(status -> cancelOnce(reservationId, userId)),
                ContentionException::new);
    }

    private CancelOutcome cancelOnce(UUID reservationId, String userId) {
        // (1) Lock the reservation; concurrent cancels of it serialise here.
        Reservation reservation =
                reservations.lockForOwner(reservationId, userId).orElseThrow(ReservationNotFoundException::new);
        if (reservation.status() == ReservationStatus.CANCELLED) {
            return new CancelOutcome(reservation, false);
        }
        int seatCount = reservation.seats().size();

        // (2) Return the seats to the user's quota.
        if (userCounts.subtract(reservation.showId(), userId, seatCount) != 1) {
            throw new IllegalStateException("No seat counter for reservation " + reservationId);
        }

        // (3) Lock the seats in label order, then free only those still pointing at this reservation.
        seats.lockForUpdate(reservation.showId(), reservation.seats());
        int released = seats.release(reservation.showId(), reservationId);
        if (released != seatCount) {
            throw new IllegalStateException("Released " + released + " of " + seatCount + " seats of " + reservationId);
        }
        return new CancelOutcome(reservations.markCancelled(reservationId), true);
    }

    public Reservation get(UUID reservationId, String userId) {
        return reservations.findByIdForOwner(reservationId, userId).orElseThrow(ReservationNotFoundException::new);
    }

    /** Input from the API layer. {@code seats} and the keys are raw: validation happens here, in a fixed order. */
    public record UserReserveRequest(
            UUID showId, String userId, String headerKey, String bodyKey, List<String> seats) {}

    /** {@code cancelledNow} is false for an idempotent re-cancel; only real cancels are counted in metrics. */
    public record CancelOutcome(Reservation reservation, boolean cancelledNow) {}

    public record ReserveOutcome(Reservation reservation, boolean replayed) {

        static ReserveOutcome created(Reservation reservation) {
            return new ReserveOutcome(reservation, false);
        }

        static ReserveOutcome replayed(Reservation reservation) {
            return new ReserveOutcome(reservation, true);
        }
    }

    private record ReserveAttempt(
            Show show,
            String userId,
            String key,
            List<String> seats,
            RequestFingerprint fingerprint,
            long amountPaise) {}
}
