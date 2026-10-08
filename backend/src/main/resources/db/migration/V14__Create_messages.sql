-- Internal mail: one person writing to another, read in the Inbox.
--
-- Notifications are derived from events and nobody owns their wording, so they
-- stay their own table. A message is written by a person to a person, and both
-- sides keep their own view of it: two soft-delete flags let either party
-- remove it from their own box without destroying the other's copy.

CREATE TABLE messages (
    id BIGSERIAL PRIMARY KEY,
    sender_id BIGINT NOT NULL,
    recipient_id BIGINT NOT NULL,
    subject VARCHAR(200) NOT NULL,
    body TEXT NOT NULL,
    read BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_by_sender BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_by_recipient BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (sender_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (recipient_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX idx_messages_recipient ON messages(recipient_id, id DESC);
CREATE INDEX idx_messages_sender ON messages(sender_id, id DESC);
CREATE INDEX idx_messages_recipient_unread
    ON messages(recipient_id)
    WHERE read = FALSE AND deleted_by_recipient = FALSE;
