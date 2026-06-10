-- M4: multiple API tokens; only SHA-256 hashes are stored, never plaintext.
create table api_token (
    id           uuid primary key default gen_random_uuid(),
    name         text not null,
    token_hash   text not null unique,
    created_at   timestamptz not null default now(),
    last_used_at timestamptz,
    revoked_at   timestamptz
);
