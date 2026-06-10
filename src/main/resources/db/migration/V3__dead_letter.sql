-- M2: dead letter queue — jobs that exhausted their retries, with full context.
create table dead_letter (
    id           bigint generated always as identity primary key,
    execution_id uuid not null references workflow_execution (id),
    step_name    text not null,
    attempts     int not null,
    last_error   text,
    payload      jsonb,
    created_at   timestamptz not null default now()
);

create index dead_letter_created_idx on dead_letter (created_at desc);
