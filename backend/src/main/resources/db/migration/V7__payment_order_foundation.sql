
-- ================================================
-- FlashTix V7: Payment Order Foundation
-- ================================================

-- A composite unique key allows payment_orders
-- to reference the reservation AND its owner.
--
-- Older reservations may have user_id = NULL.
-- Payment orders must never be created for them.

ALTER TABLE reservations
ADD CONSTRAINT uq_reservation_id_user
UNIQUE (id, user_id);


-- ================================================
-- 1. PAYMENT ORDERS
-- ================================================

CREATE TABLE payment_orders (

    id UUID PRIMARY KEY,

    reservation_id UUID NOT NULL UNIQUE,

    user_id UUID NOT NULL,

    provider VARCHAR(30) NOT NULL,

    provider_order_id VARCHAR(160),

    amount_paise BIGINT NOT NULL,

    currency VARCHAR(3) NOT NULL DEFAULT 'INR',

    status VARCHAR(20) NOT NULL DEFAULT 'CREATED',

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_payment_order_reservation_owner
        FOREIGN KEY (reservation_id, user_id)
        REFERENCES reservations (id, user_id),

    CONSTRAINT chk_payment_order_amount
        CHECK (amount_paise > 0),

    CONSTRAINT chk_payment_order_currency
        CHECK (currency = 'INR'),

    CONSTRAINT chk_payment_order_status
        CHECK (
            status IN (
                'CREATED',
                'PENDING',
                'SUCCEEDED',
                'FAILED',
                'EXPIRED'
            )
        ),

    CONSTRAINT chk_payment_order_provider
        CHECK (length(trim(provider)) > 0)
);

-- Provider order IDs are unique inside each provider.
-- PostgreSQL allows multiple NULL values here.
CREATE UNIQUE INDEX uq_payment_provider_order
ON payment_orders (provider, provider_order_id)
WHERE provider_order_id IS NOT NULL;

CREATE INDEX idx_payment_orders_user
ON payment_orders (user_id, created_at DESC);

CREATE INDEX idx_payment_orders_status
ON payment_orders (status, created_at);


-- ================================================
-- 2. PAYMENT ATTEMPTS
-- ================================================

CREATE TABLE payment_attempts (

    id UUID PRIMARY KEY,

    payment_order_id UUID NOT NULL,

    provider VARCHAR(30) NOT NULL,

    provider_payment_id VARCHAR(160),

    status VARCHAR(20) NOT NULL DEFAULT 'INITIATED',

    failure_code VARCHAR(100),

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_payment_attempt_order
        FOREIGN KEY (payment_order_id)
        REFERENCES payment_orders(id),

    CONSTRAINT chk_payment_attempt_status
        CHECK (
            status IN (
                'INITIATED',
                'SUCCEEDED',
                'FAILED'
            )
        ),

    CONSTRAINT chk_payment_attempt_provider
        CHECK (length(trim(provider)) > 0)
);

CREATE UNIQUE INDEX uq_payment_attempt_provider_id
ON payment_attempts (provider, provider_payment_id)
WHERE provider_payment_id IS NOT NULL;

CREATE INDEX idx_payment_attempt_order
ON payment_attempts (payment_order_id, created_at DESC);


-- ================================================
-- 3. PAYMENT WEBHOOK EVENTS
-- ================================================

CREATE TABLE payment_webhook_events (

    id UUID PRIMARY KEY,

    provider VARCHAR(30) NOT NULL,

    provider_event_id VARCHAR(160) NOT NULL,

    event_type VARCHAR(100) NOT NULL,

    payload_sha256 VARCHAR(64) NOT NULL,

    processing_status VARCHAR(20) NOT NULL
        DEFAULT 'RECEIVED',

    received_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    processed_at TIMESTAMPTZ,

    CONSTRAINT uq_payment_webhook_event
        UNIQUE (provider, provider_event_id),

    CONSTRAINT chk_payment_webhook_status
        CHECK (
            processing_status IN (
                'RECEIVED',
                'PROCESSED',
                'FAILED'
            )
        ),

    CONSTRAINT chk_payment_webhook_hash
        CHECK (payload_sha256 ~ '^[0-9a-f]{64}$'),

    CONSTRAINT chk_payment_webhook_processed
        CHECK (
            processed_at IS NULL
            OR processing_status = 'PROCESSED'
        )
);

CREATE INDEX idx_payment_webhook_processing
ON payment_webhook_events (
    processing_status,
    received_at
);
