package com.paytmmoney.seatreservation.domain;

public enum ReservationStatus {
    CONFIRMED("confirmed"),
    CANCELLED("cancelled");

    private final String dbValue;

    ReservationStatus(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    public static ReservationStatus fromDb(String value) {
        for (ReservationStatus status : values()) {
            if (status.dbValue.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown reservation status: " + value);
    }
}
