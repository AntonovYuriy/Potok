-- M7: per-workflow Telegram subscriptions.
--
-- workflow.subscribable opts a workflow INTO the dynamic /subscriptions menu.
-- Default FALSE so nothing leaks when M7 ships — owner publishes intentionally.
alter table workflow add column subscribable boolean not null default false;

create index workflow_subscribable_idx on workflow (subscribable) where subscribable;

-- (workflow_id, recipient_id) is the natural key — a recipient is either
-- subscribed to a workflow or not. Cascading deletes keep the table tidy:
-- deleting a recipient drops their subscriptions, deleting a workflow drops
-- everyone's subscription to it. Revoking a recipient does NOT delete the
-- recipient row — queries filter on status='APPROVED' to skip non-active ones.
create table workflow_subscription (
    id            uuid primary key default gen_random_uuid(),
    workflow_id   uuid not null references workflow (id) on delete cascade,
    recipient_id  uuid not null references telegram_recipient (id) on delete cascade,
    created_at    timestamptz not null default now(),
    unique (workflow_id, recipient_id)
);

create index workflow_subscription_workflow_idx on workflow_subscription (workflow_id);
create index workflow_subscription_recipient_idx on workflow_subscription (recipient_id);
