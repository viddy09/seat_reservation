package com.paytmmoney.seatreservation.service;

import com.paytmmoney.seatreservation.domain.ContentionException;
import com.paytmmoney.seatreservation.domain.IdempotencyKey;
import com.paytmmoney.seatreservation.domain.IdempotencyKeyReusedException;
import com.paytmmoney.seatreservation.domain.Money;
import com.paytmmoney.seatreservation.domain.PerUserLimitException;
import com.paytmmoney.seatreservation.domain.RequestFingerprint;
import com.paytmmoney.seatreservation.domain.Reservation;
import com.paytmmoney.seatreservation.domain.ReservationNotFoundException;
import com.paytmmoney.seatreservation.domain.Seat;
import com.paytmmoney.seatreservation.domain.SeatSelection;
import com.paytmmoney.seatreservation.domain.SeatStatus;
import com.paytmmoney.seatreservation.domain.SeatTakenException;
import com.paytmmoney.seatreservation.domain.Show;
import com.paytmmoney.seatreservation.domain.ShowNotFoundException;
import com.paytmmoney.seatreservation.domain.UnknownSeatException;
import com.paytmmoney.seatreservation.persistence.ReservationRepository;
import com.paytmmoney.seatreservation.persistence.SeatRepository;
import com.paytmmoney.seatreservation.persistence.ShowRepository;
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

    private final ShowRepository shows;
    private final ReservationRepository reservations;
    private final UserShowCountRepository userCounts;
    private final SeatRepository seats;
    private final TransactionTemplate transaction;
    private final TxRetry txRetry;

    public ReservationService(
            ShowRepository shows,
            ReservationRepository reservations,
            UserShowCountRepository userCounts,
            SeatRepository seats,
            TransactionTemplate transaction,
            TxRetry txRetry) {
        this.shows = shows;
        this.reservations = reservations;
        this.userCounts = userCounts;
        this.seats = seats;
        this.transaction = transaction;
        this.txRetry = txRetry;
    }

    /** {@code request.userId()} comes from the verified token only, never from the request body (I6). */
    public ReserveOutcome reserve(UserReserveRequest request) {
        // Pre-transaction checks, in the fixed order of ADR-018. None of them takes a lock: they read only
        // immutable show data (ADR-026), so they can't race with anything.
        String key = IdempotencyKey.resolve(request.headerKey(), request.bodyKey());
        List<String> sortedSeats = SeatSelection.forReservation(request.seats());
        Show show = shows.findById(request.showId()).orElseThrow(ShowNotFoundException::new);
        if (sortedSeats.size() > show.perUserLimit()) {
            throw new PerUserLimitException(show.perUserLimit());
        }
        List<String> missing = shows.findMissingSeats(show.id(), sortedSeats);
        if (!missing.isEmpty()) {
            throw new UnknownSeatException(missing);
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

    public Reservation get(UUID reservationId, String userId) {
        return reservations.findByIdForOwner(reservationId, userId).orElseThrow(ReservationNotFoundException::new);
    }

    /** Input from the API layer. {@code seats} and the keys are raw: validation happens here, in a fixed order. */
    public record UserReserveRequest(
            UUID showId, String userId, String headerKey, String bodyKey, List<String> seats) {}

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
