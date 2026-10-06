-- Employee identity: a stable human-facing code plus an optional avatar.
--
-- employee_code is the number people actually use to refer to each other, so
-- every row gets one. Existing rows are backfilled from the id (NX-0007);
-- new rows get the next value from a sequence so the application never has to
-- invent codes itself.
ALTER TABLE users ADD COLUMN employee_code VARCHAR(20);

UPDATE users SET employee_code = 'NX-' || LPAD(id::text, 4, '0') WHERE employee_code IS NULL;

-- Continue the numbering above every existing code so the sequence cannot
-- hand out a duplicate.
CREATE SEQUENCE user_employee_code_seq START WITH 1000;
SELECT setval('user_employee_code_seq', GREATEST(COALESCE(MAX(id), 0), 999) + 1, false)
FROM users;

ALTER TABLE users ALTER COLUMN employee_code SET NOT NULL;
ALTER TABLE users ALTER COLUMN employee_code
    SET DEFAULT 'NX-' || LPAD(nextval('user_employee_code_seq')::text, 4, '0');

CREATE UNIQUE INDEX ux_users_employee_code ON users (employee_code);

-- Avatars live in their own table rather than as a column on users.
-- `spring.jpa.open-in-view=false` and there is no bytecode enhancement here, so
-- a lazily-fetched blob on the users entity would either be dragged into every
-- user listing or blow up outside a transaction. A separate table makes the
-- hot path structural instead of relying on fetch semantics: listing users
-- never reads a single image byte.
CREATE TABLE user_avatars (
    user_id BIGINT PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    bytes BYTEA NOT NULL,
    content_type VARCHAR(50) NOT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);