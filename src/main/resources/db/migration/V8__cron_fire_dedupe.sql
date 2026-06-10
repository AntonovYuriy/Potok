-- M4: multi-instance cron dedupe — one claim row per (workflow, scheduled minute);
-- the replica that inserts the row fires, the rest no-op. Rows are purged nightly.
create table cron_fire (
    workflow_id uuid not null,
    fire_time   timestamptz not null,
    primary key (workflow_id, fire_time)
);
