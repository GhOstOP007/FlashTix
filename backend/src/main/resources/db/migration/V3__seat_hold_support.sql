
-- Unique token identifying a temporary seat hold
ALTER TABLE show_seats
ADD COLUMN hold_token UUID;

-- Time when the temporary hold expires
ALTER TABLE show_seats
ADD COLUMN hold_expires_at TIMESTAMPTZ;

-- Ensure hold metadata is consistent
ALTER TABLE show_seats
ADD CONSTRAINT chk_hold_metadata
CHECK (
    (
        status = 'HELD'
        AND hold_token IS NOT NULL
        AND hold_expires_at IS NOT NULL
    )
    OR
    (
        status <> 'HELD'
        AND hold_token IS NULL
        AND hold_expires_at IS NULL
    )
);

CREATE INDEX idx_show_seats_expiry
ON show_seats(hold_expires_at)
WHERE status = 'HELD';
