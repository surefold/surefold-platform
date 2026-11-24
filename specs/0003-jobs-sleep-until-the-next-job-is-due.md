# 0003 — surefold-jobs sleeps until the next job is due

**Status: Draft; owner approved building on 24 Nov 2026 in the Doorlist session** ("Yes" to "draft that spec and
build it"). Stacked on spec 0002.

## Problem

`JobWorker` sleeps a fixed `JobSettings.poll` between claim rounds, and each round issues one `Queue.claim` per
handled kind plus one for the unhandled kinds: Doorlist issues ~45 claim queries a poll while idle. A job enqueued
to run later is never `NOTIFY`ed, so it waits up to a whole poll past its run time. Services choose between a short
poll (many idle queries) and a long one (late jobs); Doorlist runs a 1 s poll.

## Not doing

- Any change to `Queue.claim`, leases, rate buckets, the per-tenant cap, or what `enqueue` notifies.
- Housekeeping: sweep, prune, cron and backlog keep `JobSettings.housekeeping`.
- Working out which due jobs could not be claimed (empty bucket, tenant cap, kind at concurrency). A due job that
  is not claimed only makes the worker poll at `minPoll`.

## Shape

`JobSettings` gains `minPoll`, defaulting to `poll`, so nothing changes until a service sets it:

```kotlin
JobSettings(poll = 1.minutes, minPoll = 50.milliseconds)
```

After each claim round, when `minPoll < poll`, the worker reads the earliest `run_at` of any pending job (any kind)
and sleeps `clamp(earliest - now, minPoll, poll)`; with no pending job, `poll`. `poll` stays the cap, with its
meaning unchanged: the longest a job enqueued later without a `NOTIFY` can wait, and #26's every-kind floor.

- `LISTEN` still wakes the worker at once, and #26's refills still claim as runs end.
- A sleep that ends by itself claims for every kind, as one that ran its whole `poll` did.
- A due job nobody can claim makes `earliest <= now`, so the worker sleeps `minPoll`: never a busy loop.
  `minPoll` must be positive.
- Cron firings are enqueued in housekeeping, before the round's query, so they are seen. A crashed run's job is
  pending again only once a sweep returns it, at housekeeping cadence as today, and is claimed in that round.
- New index `surefold_jobs_next on surefold_jobs (run_at) where status = 'pending'`, as changeset `jobs-next` in
  `postgres.sql` and `flyway/V7__jobs-next.sql`: the existing `surefold_jobs_due` leads with `priority`, so
  `min(run_at)` over it scans every pending job, and a service with many future jobs would pay that each round.

## Why this shape

The sleep follows the queue rather than a guess: idle costs one claim round per `poll`, a job due soon runs within
`minPoll` of its time, and a stuck backlog costs what a fixed `minPoll` poll costs today. The alternative, one
`exists` query before the per-kind claims, still wakes every `poll` and does nothing for late jobs. Defaulting
`minPoll` to `poll` keeps every caller and test as it is, and skips the extra query.

## Stack

- [ ] **`claude/jobs-sleep-until-due`** — `minPoll`, `Queue.nextDue`, the index, three `JobWorkerSuite` tests.
      Done when: a job due in 300 ms runs within a second at `poll = 10.minutes`; an idle worker claims once per
      `poll`; a due, rate-limited job costs at most a claim round per `minPoll`.

## Acceptance

```bash
./gradlew :surefold-jobs:build :surefold-jobs-otel:build :surefold-jobs-tenancy:build :surefold-app-jobs:build \
  :surefold-app-flyway:build
```

## Open questions

1. **Notify future jobs.** A job enqueued for later during a long sleep waits up to `poll`. `NOTIFY` on every
   enqueue would let a listening worker recompute its sleep at once, at the cost of a claim round per enqueue on
   every worker. Recommended: not now; Doorlist keeps `poll` at a few seconds and sets `minPoll`.
2. **The index.** Without it the query is correct but scans the pending jobs. Recommended: keep it; its write cost
   is one more partial index entry per pending row.
3. **Defaults.** Recommended: leave `minPoll = poll` here, and let Doorlist try `poll = 5.seconds,
   minPoll = 50.milliseconds` before changing the library default.
4. **Version.** Recommended: snapshot only, as with 0002.
