-- One token bucket per kind with a rate (spec 0217), shared by every worker: a claim locks its row, refills it from
-- the clock, and takes what it claimed, in one transaction.
create table surefold_jobs_buckets (
    kind        text             primary key,
    tokens      double precision not null,
    refilled_at timestamptz      not null
);
