
-- =============================================
-- FlashTix V8: Payment Compensation Cases
-- =============================================

CREATE TABLE payment_compensation_cases (

    id UUID PRIMARY KEY,

    payment_order_id UUID NOT NULL,

    provider VARCHAR(30) NOT NULL,

    provider_payment_id VARCHAR(160) NOT NULL,

    reason VARCHAR(40) NOT NULL,

    status VARCHAR(20) NOT NULL DEFAULT 'OPEN',

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    resolved_at TIMESTAMPTZ,

    CONSTRAINT fk_compensation_payment_order
        FOREIGN KEY (payment_order_id)
        REFERENCES payment_orders(id),

    CONSTRAINT uq_compensation_provider_payment
        UNIQUE (provider, provider_payment_id),

    CONSTRAINT uq_compensation_payment_order
        UNIQUE (payment_order_id),

    CONSTRAINT chk_compensation_reason
        CHECK (
            reason IN (
                'LATE_CAPTURE',
                'RESERVATION_CANCELLED',
                'SEATS_UNAVAILABLE'
            )
        ),

    CONSTRAINT chk_compensation_status
        CHECK (
            status IN (
                'OPEN',
                'UNDER_REVIEW',
                'REFUND_PENDING',
                'RESOLVED'
            )
        ),

    CONSTRAINT chk_compensation_resolution
        CHECK (
            (status = 'RESOLVED' AND resolved_at IS NOT NULL)
            OR
            (status <> 'RESOLVED' AND resolved_at IS NULL)
        )
);

CREATE INDEX idx_compensation_open
ON payment_compensation_cases (
    status,
    created_at
);
