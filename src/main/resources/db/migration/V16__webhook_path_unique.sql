-- M13: two ENABLED workflows sharing a webhook path made POST /hooks/{path} throw
-- (IncorrectResultSizeDataAccessException -> 500) and silenced BOTH workflows.
-- Uniqueness is now validated at create/update/enable (409); this index is the
-- concurrent-create backstop.

-- Existing duplicates are already broken (every delivery 500s): keep the oldest
-- workflow per path, disable the rest so the unique index can be created.
update workflow w
set enabled = false, updated_at = now()
where enabled
  and definition -> 'trigger' -> 'webhook' ->> 'path' is not null
  and exists (select 1
              from workflow other
              where other.enabled
                and other.id != w.id
                and other.definition -> 'trigger' -> 'webhook' ->> 'path' =
                    w.definition -> 'trigger' -> 'webhook' ->> 'path'
                and other.created_at < w.created_at);

drop index if exists workflow_webhook_path_idx;
create unique index workflow_webhook_path_idx
    on workflow ((definition -> 'trigger' -> 'webhook' ->> 'path'))
    where enabled and definition -> 'trigger' -> 'webhook' ->> 'path' is not null;
