package com.paytmmoney.seatreservation.domain;

/**
 * Seat state machine: {@code available → held → confirmed}, and back to {@code available} on cancel or hold expiry.
 * v1 goes straight from available to confirmed; {@code held} exists so TTL holds are an additive change (ADR-011).
 */
public enum SeatStatus {
    AVAILABLE("available"),
    HELD("held"),
    CONFIRMED("confirmed");

    private final String dbValue;

    SeatStatus(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    public static SeatStatus fromDb(String value) {
        for (SeatStatus status : values()) {
            if (status.dbValue.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown seat status: " + value);
    }
}
