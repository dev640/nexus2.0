-- Alerts became clickable: a notification now carries an app-relative path to
-- the thing it is about (a task on the board, a wiki page, a chat message), so
-- clicking the popup lands on the work instead of the app's front door.
-- Nullable because notifications written before this migration, and anything
-- with no page of its own, have nowhere to go.
ALTER TABLE notifications
    ADD COLUMN link VARCHAR(300);
