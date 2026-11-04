-- How far a long job has got (spec 0219): what its runs have done, added up, and its total when a run knows it. Kept
-- in history too, so a finished operation still says how much it did.
alter table surefold_jobs add column progress_done bigint not null default 0;
alter table surefold_jobs add column progress_total bigint;
alter table surefold_jobs_history add column progress_done bigint not null default 0;
alter table surefold_jobs_history add column progress_total bigint;
