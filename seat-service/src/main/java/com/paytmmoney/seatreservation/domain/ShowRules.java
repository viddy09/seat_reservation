package com.paytmmoney.seatreservation.domain;

/** Limits for creating a show (docs/DESIGN.md A18). */
public final class ShowRules {

    public static final int MAX_SEATS = 10_000;
    public static final int DEFAULT_PER_USER_LIMIT = 4;
    public static final int MAX_PER_USER_LIMIT = 100;
    public static final int MAX_NAME_LENGTH = 200;

    private ShowRules() {}
}
