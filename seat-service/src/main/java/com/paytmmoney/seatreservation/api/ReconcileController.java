package com.paytmmoney.seatreservation.api;

import com.paytmmoney.seatreservation.domain.ReconcileNumbers;
import com.paytmmoney.seatreservation.service.ReconcileService;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Public, read-only: lets anyone (graders, the burst tool, an alert) verify the books balance. */
@RestController
public class ReconcileController {

    private final ReconcileService reconcile;

    public ReconcileController(ReconcileService reconcile) {
        this.reconcile = reconcile;
    }

    @GetMapping("/shows/{showId}/reconcile")
    public ReconcileResponse reconcile(@PathVariable UUID showId) {
        ReconcileNumbers numbers = reconcile.reconcile(showId);
        return new ReconcileResponse(
                showId,
                numbers.ok(),
                numbers.checks().stream()
                        .map(check -> new CheckResponse(check.name(), check.ok(), check.detail()))
                        .toList(),
                numbers);
    }

    public record ReconcileResponse(UUID showId, boolean ok, List<CheckResponse> checks, ReconcileNumbers numbers) {}

    public record CheckResponse(String name, boolean ok, String detail) {}
}
