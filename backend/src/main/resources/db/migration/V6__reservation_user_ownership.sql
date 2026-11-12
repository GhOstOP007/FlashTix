
-- FlashTix V6: Authenticated reservation ownership

-- Existing development reservations must remain intact.
-- Therefore user_id is nullable during migration.
ALTER TABLE reservations
ADD COLUMN user_id UUID;

ALTER TABLE reservations
ADD CONSTRAINT fk_reservation_user
FOREIGN KEY (user_id)
REFERENCES users(id);

-- Prevent two reservations from using the same
-- idempotency key for the same authenticated user.
CREATE UNIQUE INDEX uq_reservation_user_idempotency
ON reservations (user_id, idempotency_key)
WHERE user_id IS NOT NULL;

-- Efficient retrieval of authenticated booking history.
CREATE INDEX idx_reservations_user_history
ON reservations (user_id, created_at DESC);
