-- Durable waits + approvals: WAITING becomes a first-class status.
alter table workflow_execution drop constraint workflow_execution_status_check;
alter table workflow_execution add constraint workflow_execution_status_check
    check (status in ('PENDING', 'RUNNING', 'WAITING', 'SUCCEEDED', 'FAILED'));

alter table step_execution drop constraint step_execution_status_check;
alter table step_execution add constraint step_execution_status_check
    check (status in ('PENDING', 'RUNNING', 'WAITING', 'SUCCEEDED', 'FAILED', 'SKIPPED'));

-- Human-in-the-loop approvals: one row per waiting approval step.
-- Tokens are stored as SHA-256 hashes only; plaintext lives in the Telegram
-- message links and nowhere else. One-time use: decided_at set exactly once.
create table approval (
    id            uuid primary key default gen_random_uuid(),
    execution_id  uuid not null references workflow_execution (id) on delete cascade,
    step_name     text not null,
    workflow_name text not null,
    approve_hash  text not null,
    deny_hash     text not null,
    expires_at    timestamptz not null,
    decided_at    timestamptz,
    decision      text check (decision in ('approved', 'denied', 'timed_out')),
    created_at    timestamptz not null default now(),
    unique (execution_id, step_name)
);

create index approval_approve_hash_idx on approval (approve_hash);
create index approval_deny_hash_idx on approval (deny_hash);
