-- Web Push subscriptions: where to send a notification for a browser that has
-- agreed to receive them.
--
-- One row per browser, not per account: the same person on a laptop and a phone
-- has two endpoints, and silencing the phone must not silence the laptop.
--
-- No migration can backfill these. A subscription is created by the browser that
-- will receive the messages — it holds the private half of the key pair — so the
-- table starts empty and only ever fills for people who press "Enable
-- notifications".
CREATE TABLE push_subscriptions (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    -- Issued by the push service (FCM, Mozilla, Apple). Opaque, long, and unique
    -- per browser: it is both the address and the identity of a device.
    endpoint VARCHAR(1000) NOT NULL,
    -- The browser's public key for the message payload and the shared auth
    -- secret that authenticates the encryption. Both are base64url.
    p256dh VARCHAR(300) NOT NULL,
    auth VARCHAR(300) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_push_subscriptions_endpoint UNIQUE (endpoint)
);

-- Deleting an account takes its devices with it (ON DELETE CASCADE above), and
-- notifications fan out by user id, so that is the index that matters.
CREATE INDEX idx_push_subscriptions_user ON push_subscriptions (user_id);
