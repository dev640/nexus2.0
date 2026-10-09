-- Mail written to the whole workspace.
--
-- A broadcast is still one row per recipient — each person owns their own copy
-- and clears it independently — but the flag is what lets a reader tell a letter
-- sent to everyone from one addressed to them personally. Without it the sender's
-- Sent box could not say "To Everyone", and a recipient's copy is indistinguishable
-- from ordinary mail.
--
-- Existing rows are all person-to-person, so the default is false.

ALTER TABLE messages
    ADD COLUMN is_broadcast BOOLEAN NOT NULL DEFAULT FALSE;
