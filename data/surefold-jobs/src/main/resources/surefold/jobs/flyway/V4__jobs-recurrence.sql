-- A recurring job's rule (spec 0218): its cron and zone, from which finishing one occurrence enqueues the next.
alter table surefold_jobs add column recurrence text;
