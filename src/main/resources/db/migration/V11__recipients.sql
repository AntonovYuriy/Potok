-- M6: Telegram recipient directory + key/value settings.
--
-- A recipient row exists for every Telegram chat that has messaged the bot.
-- It governs WHO RECEIVES bot messages, NOT who controls Potok — API/dashboard
-- stay behind X-API-Key / api_token. A chat can never gain control by talking
-- to the bot.
create table telegram_recipient (
    id            uuid primary key default gen_random_uuid(),
    chat_id       text not null unique,
    display_name  text not null,
    status        text not null check (status in ('PENDING', 'APPROVED', 'REVOKED')),
    source        text not null default 'telegram',
    created_at    timestamptz not null default now(),
    approved_at   timestamptz,
    last_seen_at  timestamptz not null default now()
);

create index telegram_recipient_status_idx on telegram_recipient (status);

-- Tiny k/v settings table. One row per setting, jsonb value for forward room.
-- Currently only telegram_auto_approve (bool, default false) is read.
create table setting (
    key        text primary key,
    value      jsonb not null,
    updated_at timestamptz not null default now()
);

insert into setting (key, value) values ('telegram_auto_approve', 'false'::jsonb);
