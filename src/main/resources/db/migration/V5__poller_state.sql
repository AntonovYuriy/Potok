-- M3 pollers: per-workflow poll state and RSS dedupe, both survive restarts.

create table poll_state (
    workflow_id    uuid primary key references workflow (id),
    last_hash      text,
    last_condition boolean,
    last_polled_at timestamptz not null default now()
);

create table rss_seen (
    workflow_id uuid not null references workflow (id),
    item_id     text not null,
    seen_at     timestamptz not null default now(),
    primary key (workflow_id, item_id)
);
