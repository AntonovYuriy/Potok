-- M4: append-only workflow version history; executions snapshot their definition.

create table workflow_version (
    workflow_id uuid not null references workflow (id),
    version_no  int not null,
    yaml_source text not null,
    definition  jsonb not null,
    comment     text,
    created_at  timestamptz not null default now(),
    primary key (workflow_id, version_no)
);

alter table workflow add column current_version int not null default 1;

-- every existing workflow becomes version 1
insert into workflow_version (workflow_id, version_no, yaml_source, definition, comment)
select id, 1, yaml_source, definition, 'initial (backfilled)' from workflow;

-- executions pin the version they run and snapshot the parsed definition:
-- a PUT mid-run no longer changes what a running execution does
alter table workflow_execution add column version_no int;
alter table workflow_execution add column definition jsonb;
