-- When the next pending job is due (spec 0003), which a worker reads to sleep until then: surefold_jobs_due leads
-- with priority, so its min(run_at) scans every pending job.
create index surefold_jobs_next on surefold_jobs (run_at) where status = 'pending';
