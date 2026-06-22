-- Dashboard-managed SMTP config (M9). Single row enforced by a boolean PK that
-- can only be true. NULL/empty columns = not configured; the email client then
-- falls back to the SMTP_* env vars. The password is stored AES-GCM encrypted
-- (POTOK_SECRET_KEY) and is NEVER returned by the API.
create table smtp_config (
    id                 boolean primary key default true,
    host               text,
    port               int,
    username           text,
    from_address       text,
    starttls           boolean not null default true,
    auth               boolean not null default true,
    password_encrypted text,
    updated_at         timestamptz not null default now(),
    constraint smtp_config_singleton check (id)
);
