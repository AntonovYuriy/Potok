-- Telegram inline buttons: remember which message carries the keyboard so a
-- decision can edit it (buttons disappear, result line appears). question is
-- kept because editMessageText needs the full new text.
alter table approval add column chat_id text;
alter table approval add column message_id bigint;
alter table approval add column question text;
