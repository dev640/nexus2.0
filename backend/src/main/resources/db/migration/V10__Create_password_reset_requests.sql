-- Admin-mediated password resets.
--
-- Accounts sign in through Supabase, which owns their password, so a user who
-- forgets it cannot set a new one themselves. Instead they file a request and an
-- admin generates a replacement. This table is that queue.
--
-- The generated password is deliberately NOT stored: it is shown to the admin
-- once and handed over out of band, then discarded.

CREATE TABLE password_reset_requests (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    requested_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved_at TIMESTAMP,
    resolved_by BIGINT,
    note TEXT,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (resolved_by) REFERENCES users(id) ON DELETE SET NULL
);

-- One open request per user: asking again should not flood the admin queue.
CREATE UNIQUE INDEX uq_password_reset_requests_pending
    ON password_reset_requests (user_id)
    WHERE status = 'PENDING';

-- The admin queue reads pending requests newest first.
CREATE INDEX idx_password_reset_requests_status
    ON password_reset_requests (status, requested_at DESC);