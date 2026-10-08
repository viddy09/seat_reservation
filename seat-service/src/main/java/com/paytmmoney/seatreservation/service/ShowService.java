package com.paytmmoney.seatreservation.service;

import com.paytmmoney.seatreservation.domain.NewShow;
import com.paytmmoney.seatreservation.domain.Seat;
import com.paytmmoney.seatreservation.domain.SeatCounts;
import com.paytmmoney.seatreservation.domain.SeatStatus;
import com.paytmmoney.seatreservation.domain.Show;
import com.paytmmoney.seatreservation.domain.ShowNotFoundException;
import com.paytmmoney.seatreservation.persistence.ShowRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Use cases: create a show, view a show with per-seat status and counts. */
@Service
public class ShowService {

    private final ShowRepository shows;
    private final TransactionTemplate transaction;

    public ShowService(ShowRepository shows, TransactionTemplate transaction) {
        this.shows = shows;
        this.transaction = transaction;
    }

    /** Show row and every seat are inserted in one transaction: a show is never visible without its seats. */
    public ShowView create(NewShow newShow) {
        UUID id = UUID.randomUUID();
        Show show = transaction.execute(status -> shows.insert(id, newShow));
        List<Seat> seats = newShow.seats().stream()
                .map(label -> new Seat(label, SeatStatus.AVAILABLE))
                .toList();
        return new ShowView(show, seats, SeatCounts.of(seats));
    }

    public ShowView get(UUID showId) {
        Show show = shows.findById(showId).orElseThrow(ShowNotFoundException::new);
        List<Seat> seats = shows.findSeats(showId);
        return new ShowView(show, seats, SeatCounts.of(seats));
    }

    public record ShowView(Show show, List<Seat> seats, SeatCounts counts) {

        public ShowView {
            seats = List.copyOf(seats);
        }
    }
}
