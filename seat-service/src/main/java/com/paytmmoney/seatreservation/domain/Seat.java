package com.paytmmoney.seatreservation.domain;

/** Public view of a seat: label and status only; who holds it is not exposed (docs/DESIGN.md A15). */
public record Seat(String label, SeatStatus status) {}
