
-- FlashTix V5: User accounts

CREATE TABLE users (
    id UUID PRIMARY KEY,

    full_name VARCHAR(120) NOT NULL,

    email VARCHAR(255) NOT NULL,

    password_hash VARCHAR(255) NOT NULL,

    role VARCHAR(20) NOT NULL DEFAULT 'USER',

    enabled BOOLEAN NOT NULL DEFAULT TRUE,

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_user_role
        CHECK (role IN ('USER', 'ADMIN')),

    CONSTRAINT chk_user_email_normalized
        CHECK (email = LOWER(TRIM(email)))
);

CREATE UNIQUE INDEX uq_users_email
ON users (email);
