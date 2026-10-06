-- Slack-style chat: channels (public, private, DMs), messages and reactions.

CREATE TABLE chat_channels (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(80),
    type VARCHAR(20) NOT NULL DEFAULT 'PUBLIC',
    topic VARCHAR(255),
    created_by VARCHAR(100),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_chat_channels_type ON chat_channels(type);

-- Membership: who can read/post a channel. DMs create one channel row per
-- conversation plus one member row per participant.
CREATE TABLE chat_channel_members (
    id BIGSERIAL PRIMARY KEY,
    channel_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    is_admin BOOLEAN NOT NULL DEFAULT FALSE,
    last_read_message_id BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (channel_id) REFERENCES chat_channels(id) ON DELETE CASCADE,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX idx_chat_channel_members_channel ON chat_channel_members(channel_id);
CREATE INDEX idx_chat_channel_members_user ON chat_channel_members(user_id);
CREATE UNIQUE INDEX uq_chat_channel_members ON chat_channel_members(channel_id, user_id);

CREATE TABLE chat_messages (
    id BIGSERIAL PRIMARY KEY,
    channel_id BIGINT NOT NULL,
    author_id BIGINT,
    body TEXT NOT NULL,
    -- emoji -> list of user ids who reacted (kept inline; volume is small)
    reactions TEXT NOT NULL DEFAULT '{}',
    edited BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (channel_id) REFERENCES chat_channels(id) ON DELETE CASCADE,
    FOREIGN KEY (author_id) REFERENCES users(id) ON DELETE SET NULL
);

CREATE INDEX idx_chat_messages_channel ON chat_messages(channel_id, id DESC);
CREATE INDEX idx_chat_messages_author ON chat_messages(author_id);

CREATE TRIGGER update_chat_channels_updated_at
    BEFORE UPDATE ON chat_channels
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
