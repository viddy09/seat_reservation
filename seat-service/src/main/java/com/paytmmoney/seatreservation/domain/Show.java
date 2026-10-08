package com.paytmmoney.seatreservation.domain;

import java.time.Instant;
import java.util.UUID;

/** A show. Immutable after creation, which makes pre-transaction reads of it race-free (ADR-026). */
public record Show(UUID id, String name, long pricePaise, int perUserLimit, int totalSeats, Instant createdAt) {}
