package com.paytmmoney.seatreservation.api;

import com.paytmmoney.seatreservation.domain.NewShow;
import com.paytmmoney.seatreservation.service.ShowService;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ShowController {

    private final ShowService shows;

    public ShowController(ShowService shows) {
        this.shows = shows;
    }

    /** Admin only (enforced by the security filter chain). */
    @PostMapping("/shows")
    public ResponseEntity<ShowResponse> create(@RequestBody CreateShowRequest request) {
        NewShow newShow = NewShow.of(request.name(), request.pricePaise(), request.perUserLimit(), request.seats());
        ShowResponse body = ShowResponse.from(shows.create(newShow));
        return ResponseEntity.created(URI.create("/shows/" + body.id())).body(body);
    }

    @GetMapping("/shows/{showId}")
    public ShowResponse get(@PathVariable UUID showId) {
        return ShowResponse.from(shows.get(showId));
    }

    /**
     * Raw request: boxed fields so a missing value is distinguishable from zero. Rules are checked by
     * {@link NewShow#of} and reported as 422.
     */
    public record CreateShowRequest(String name, Long pricePaise, Integer perUserLimit, List<String> seats) {}
}
