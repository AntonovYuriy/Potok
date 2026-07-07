-- M12: the unfiltered dashboard list (GET /api/executions without workflowId) orders by
-- created_at desc. The existing (workflow_id, created_at desc) index can't serve that
-- (wrong leading column), so it was a full scan + top-N sort. This index serves it.
-- The composite index stays for the workflowId-filtered variant.
create index workflow_execution_created_at_idx on workflow_execution (created_at desc);
