-- Long-running operations (spec 0219): whether a job was started as a dry run, and who started each operation with
-- what. The audit row is kept when the job's history is pruned.
alter table surefold_jobs add column dry_run boolean not null default false;
alter table surefold_jobs_history add column dry_run boolean not null default false;
create table surefold_jobs_operations (
    id          uuid        primary key,
    tenant_id   text,
    kind        text        not null,
    started_by  text        not null,
    parameters  jsonb       not null,
    dry_run     boolean     not null,
    started_at  timestamptz not null
);
create index surefold_jobs_operations_started on surefold_jobs_operations (started_at);
