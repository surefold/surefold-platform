--liquibase formatted sql

-- surefold-jobs' tables on Postgres 15 or newer, as a Liquibase changelog: include it from the service's own
-- (`<include file="surefold/jobs/postgres.sql"/>`). Nothing creates these tables at start.

--changeset surefold:jobs
-- Every job not yet finished (spec 0216). A finished one moves to surefold_jobs_history, freeing its key.
create table surefold_jobs (
    id              uuid        primary key,
    tenant_id       text,
    kind            text        not null,
    dedupe_key      text        not null,
    payload         jsonb       not null,
    payload_version integer     not null,
    -- What the enqueuing thread carried, its trace among it: a flat object of strings, null when nothing.
    carried         jsonb,
    run_at          timestamptz not null,
    expires_at      timestamptz,
    priority        integer     not null,
    status          text        not null check (status in ('pending', 'running')),
    attempts        integer     not null default 0,
    max_attempts    integer     not null,
    locked_until    timestamptz,
    locked_by       text,
    last_error      text,
    created_at      timestamptz not null,
    updated_at      timestamptz not null,
    -- Nulls not distinct, so a job with no tenant deduplicates too: why this needs Postgres 15.
    constraint surefold_jobs_key unique nulls not distinct (tenant_id, kind, dedupe_key)
);
create index surefold_jobs_due on surefold_jobs (priority desc, run_at) where status = 'pending';

-- Every finished job, until its retention passes.
create table surefold_jobs_history (
    id              uuid        primary key,
    tenant_id       text,
    kind            text        not null,
    dedupe_key      text        not null,
    payload         jsonb       not null,
    payload_version integer     not null,
    carried         jsonb,
    run_at          timestamptz not null,
    expires_at      timestamptz,
    priority        integer     not null,
    status          text        not null check (status in ('done', 'failed', 'cancelled', 'skipped')),
    attempts        integer     not null,
    max_attempts    integer     not null,
    locked_by       text,
    last_error      text,
    created_at      timestamptz not null,
    updated_at      timestamptz not null,
    finished_at     timestamptz not null
);
create index surefold_jobs_history_finished on surefold_jobs_history (finished_at);

--changeset surefold:jobs-leases
-- What a sweep reads: the running jobs, by when their leases end.
create index surefold_jobs_leases on surefold_jobs (locked_until) where status = 'running';

--changeset surefold:jobs-buckets
-- One token bucket per kind with a rate (spec 0217), shared by every worker: a claim locks its row, refills it from
-- the clock, and takes what it claimed, in one transaction.
create table surefold_jobs_buckets (
    kind        text             primary key,
    tokens      double precision not null,
    refilled_at timestamptz      not null
);

--changeset surefold:jobs-recurrence
-- A recurring job's rule (spec 0218): its cron and zone, from which finishing one occurrence enqueues the next.
alter table surefold_jobs add column recurrence text;

--changeset surefold:jobs-progress
-- How far a long job has got (spec 0219): what its runs have done, added up, and its total when a run knows it. Kept
-- in history too, so a finished operation still says how much it did.
alter table surefold_jobs add column progress_done bigint not null default 0;
alter table surefold_jobs add column progress_total bigint;
alter table surefold_jobs_history add column progress_done bigint not null default 0;
alter table surefold_jobs_history add column progress_total bigint;
