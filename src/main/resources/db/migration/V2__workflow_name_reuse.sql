-- M2: workflow names unique only among active workflows.
-- Soft-deleted (enabled=false) workflows free up their name; old executions
-- keep referencing the old workflow id.
alter table workflow drop constraint if exists workflow_name_key;
create unique index workflow_name_active_idx on workflow (name) where enabled;
