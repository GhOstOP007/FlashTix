
-- =====================================================
-- FlashTix - V4 Reservation Management Schema
-- =====================================================

-- 1. Main reservation table
CREATE TABLE reservations (

    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    show_id BIGINT NOT NULL,

    -- Temporary development identity.
    -- Will be associated with authenticated users later.
    client_session_id UUID NOT NULL,

    -- Used to detect repeated booking requests
    idempotency_key UUID NOT NULL,

    -- SHA-256 fingerprint of normalized reservation input
    request_hash VARCHAR(64) NOT NULL,

    status VARCHAR(20) NOT NULL DEFAULT 'HELD',

    total_price NUMERIC(12, 2) NOT NULL,

    expires_at TIMESTAMPTZ NOT NULL,

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_reservation_show
        FOREIGN KEY (show_id)
        REFERENCES event_shows(id),

    CONSTRAINT chk_reservation_status
        CHECK (
            status IN (
                'HELD',
                'CONFIRMED',
                'CANCELLED',
                'EXPIRED'
            )
        ),

    CONSTRAINT chk_reservation_price
        CHECK (total_price >= 0),

    CONSTRAINT chk_request_hash
        CHECK (request_hash ~ '^[0-9a-f]{64}$'),

    CONSTRAINT uq_reservation_idempotency
        UNIQUE (client_session_id, idempotency_key),

    -- Supports show-aware composite foreign keys
    CONSTRAINT uq_reservation_id_show
        UNIQUE (id, show_id)
);


-- 2. Add a show-aware key to existing inventory
ALTER TABLE show_seats
ADD CONSTRAINT uq_show_seat_id_show
UNIQUE (id, show_id);


-- 3. Reservation-seat relationship table
CREATE TABLE reservation_seats (

    reservation_id UUID NOT NULL,

    show_id BIGINT NOT NULL,

    show_seat_id BIGINT NOT NULL,

    price_at_reservation NUMERIC(10, 2) NOT NULL,

    CONSTRAINT pk_reservation_seats
        PRIMARY KEY (
            reservation_id,
            show_seat_id
        ),

    CONSTRAINT fk_reservation_seats_reservation
        FOREIGN KEY (
            reservation_id,
            show_id
        )
        REFERENCES reservations (
            id,
            show_id
        ),

    CONSTRAINT fk_reservation_seats_show_seat
        FOREIGN KEY (
            show_seat_id,
            show_id
        )
        REFERENCES show_seats (
            id,
            show_id
        ),

    CONSTRAINT chk_reservation_seat_price
        CHECK (price_at_reservation >= 0)
);


-- 4. Index for fetching reservation history
CREATE INDEX idx_reservations_client
ON reservations (
    client_session_id,
    created_at DESC
);


-- 5. Index for expiration processing
CREATE INDEX idx_reservations_active_expiry
ON reservations (expires_at)
WHERE status = 'HELD';


-- 6. Index for reservation seats lookup
CREATE INDEX idx_reservation_seats_inventory
ON reservation_seats (show_seat_id);
