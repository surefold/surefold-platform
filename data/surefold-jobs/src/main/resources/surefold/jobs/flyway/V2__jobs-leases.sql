-- What a sweep reads: the running jobs, by when their leases end.
create index surefold_jobs_leases on surefold_jobs (locked_until) where status = 'running';
