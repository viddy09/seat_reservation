package com.paytmmoney.seatreservation.domain;

import java.util.List;

/** A validated request to create a show. Seats are sorted and unique. */
public record NewShow(String name, long pricePaise, int perUserLimit, List<String> seats) {

    public NewShow {
        seats = List.copyOf(seats);
    }

    public static NewShow of(String name, Long pricePaise, Integer perUserLimit, List<String> seats) {
        if (name == null || name.isBlank() || name.length() > ShowRules.MAX_NAME_LENGTH) {
            throw new RequestValidationException(
                    "name", "must be 1-" + ShowRules.MAX_NAME_LENGTH + " characters and not blank");
        }
        if (pricePaise == null || pricePaise <= 0) {
            throw new RequestValidationException("price_paise", "must be a positive integer number of paise");
        }
        int limit = perUserLimit == null ? ShowRules.DEFAULT_PER_USER_LIMIT : perUserLimit;
        if (limit < 1 || limit > ShowRules.MAX_PER_USER_LIMIT) {
            throw new RequestValidationException(
                    "per_user_limit", "must be between 1 and " + ShowRules.MAX_PER_USER_LIMIT);
        }
        List<String> sorted = SeatSelection.forNewShow(seats);
        // Fail at creation, not at the first large reservation: the most expensive reservation must fit in a long.
        Money.total(pricePaise, Math.min(limit, sorted.size()));
        return new NewShow(name, pricePaise, limit, sorted);
    }
}
