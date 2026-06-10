-- Potok M1 schema: workflow definitions, execution state, postgres-backed job queue.

create table workflow (
    id          uuid primary key default gen_random_uuid(),
    name        text not null unique,
    enabled     boolean not null default true,
    yaml_source text not null,
    definition  jsonb not null,
    created_at  timestamptz not null default now(),
    updated_at  timestamptz not null default now()
);

create table workflow_execution (
    id           uuid primary key default gen_random_uuid(),
    workflow_id  uuid not null references workflow (id),
    status       text not null default 'PENDING'
                 check (status in ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED')),
    trigger_info jsonb not null default '{}',
    started_at   timestamptz,
    finished_at  timestamptz,
    created_at   timestamptz not null default now()
);

create index workflow_execution_workflow_idx on workflow_execution (workflow_id, created_at desc);

create table step_execution (
    id           uuid primary key default gen_random_uuid(),
    execution_id uuid not null references workflow_execution (id),
    step_name    text not null,
    status       text not null default 'PENDING'
                 check (status in ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'SKIPPED')),
    attempt      int not null default 0,
    input        jsonb,
    output       jsonb,
    error        text,
    started_at   timestamptz,
    finished_at  timestamptz,
    created_at   timestamptz not null default now(),
    unique (execution_id, step_name)
);

create table job_queue (
    id           bigint generated always as identity primary key,
    execution_id uuid not null references workflow_execution (id),
    step_name    text not null,
    run_at       timestamptz not null default now(),
    attempts     int not null default 0,
    locked_until timestamptz,
    created_at   timestamptz not null default now()
);

create index job_queue_poll_idx on job_queue (run_at, locked_until);

-- Webhook trigger lookup: definition -> trigger -> webhook -> path
create index workflow_webhook_path_idx
    on workflow ((definition -> 'trigger' -> 'webhook' ->> 'path'))
    where enabled;
