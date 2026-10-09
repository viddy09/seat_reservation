package com.paytmmoney.seatreservation.service;

import com.paytmmoney.seatreservation.domain.ReconcileNumbers;
import com.paytmmoney.seatreservation.domain.ShowNotFoundException;
import com.paytmmoney.seatreservation.persistence.ReconcileRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Use case: check a show's books (counts, reservations, per-user counters) agree with each other. */
@Service
public class ReconcileService {

    private final ReconcileRepository reconcile;

    public ReconcileService(ReconcileRepository reconcile) {
        this.reconcile = reconcile;
    }

    public ReconcileNumbers reconcile(UUID showId) {
        return reconcile.numbers(showId).orElseThrow(ShowNotFoundException::new);
    }
}
