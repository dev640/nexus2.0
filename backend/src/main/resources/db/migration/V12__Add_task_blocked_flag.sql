-- A task can be blocked independently of its status.
--
-- "Blocked" is not a TaskStatus on purpose: a blocked task is still IN_PROGRESS
-- or TODO, and promoting it to a new status column would destroy the
-- distinction between what someone is working on and what they are waiting on.
-- Keeping it a flag lets both questions be answered at once.
ALTER TABLE tasks ADD COLUMN blocked BOOLEAN NOT NULL DEFAULT FALSE;

-- Blocked work is what the Copilot and risk reports surface first, and the
-- common query is "what is blocked in this project", so index it.
CREATE INDEX idx_tasks_blocked ON tasks (blocked) WHERE blocked;