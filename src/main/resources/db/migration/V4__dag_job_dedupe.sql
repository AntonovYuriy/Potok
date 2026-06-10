-- M3 DAG: two branches finishing at once may both compute the same join step
-- as ready; this index turns the second enqueue into a no-op (ON CONFLICT).
create unique index job_queue_exec_step_uniq on job_queue (execution_id, step_name);
