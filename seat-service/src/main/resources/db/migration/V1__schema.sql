-- Seat reservation schema. See docs/DESIGN.md §4 and docs/DECISIONS.md.
-- Constraints here are defence in depth: even a buggy code path cannot persist an inconsistent seat.

CREATE TABLE shows (
    id              uuid        PRIMARY KEY,
    name            text        NOT NULL CHECK (length(name) BETWEEN 1 AND 200),
    price_paise     bigint      NOT NULL CHECK (price_paise > 0),
    per_user_limit  int         NOT NULL CHECK (per_user_limit >= 1),
    total_seats     int         NOT NULL CHECK (total_seats >= 1),
    created_at      timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX shows_created_at_idx ON shows (created_at DESC);

CREATE TABLE seats (
    show_id          uuid        NOT NULL REFERENCES shows (id),
    -- COLLATE "C": ORDER BY seat_label is byte order, identical to Java String.compareTo for ASCII labels and
    -- independent of the server locale. The global lock order depends on it (ADR-017).
    seat_label       text COLLATE "C" NOT NULL,
    status           text        NOT NULL DEFAULT 'available'
                                 CHECK (status IN ('available', 'held', 'confirmed')),
    reservation_id   uuid        NULL,
    user_id          text        NULL,
    hold_expires_at  timestamptz NULL,
    updated_at       timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (show_id, seat_label),
    CONSTRAINT seats_owner_iff_taken CHECK ((status = 'available') = (reservation_id IS NULL)),
    CONSTRAINT seats_user_iff_taken CHECK ((status = 'available') = (user_id IS NULL)),
    CONSTRAINT seats_expiry_iff_held CHECK ((status = 'held') = (hold_expires_at IS NOT NULL))
);

CREATE INDEX seats_show_status_idx ON seats (show_id, status);
CREATE INDEX seats_reservation_idx ON seats (reservation_id) WHERE reservation_id IS NOT NULL;

CREATE TABLE reservations (
    id               uuid        PRIMARY KEY,
    show_id          uuid        NOT NULL REFERENCES shows (id),
    user_id          text        NOT NULL,
    idempotency_key  text        NOT NULL,
    request_hash     bytea       NOT NULL CHECK (length(request_hash) = 32),
    seats            text[]      NOT NULL CHECK (cardinality(seats) >= 1),
    amount_paise     bigint      NOT NULL CHECK (amount_paise > 0),
    status           text        NOT NULL CHECK (status IN ('confirmed', 'cancelled')),
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    -- Exactly-once: a concurrent insert with the same (user, key) blocks on this index until the first commits or
    -- rolls back (ADR-008).
    CONSTRAINT reservations_user_key_uq UNIQUE (user_id, idempotency_key)
);

CREATE INDEX reservations_show_idx ON reservations (show_id);

CREATE TABLE user_show_counts (
    show_id     uuid NOT NULL REFERENCES shows (id),
    user_id     text NOT NULL,
    seat_count  int  NOT NULL CHECK (seat_count >= 0),
    PRIMARY KEY (show_id, user_id)
);
