# 0002 — surefold-jobs claims again when a kind fills its room

**Status: Draft for the owner's edit.** Asked for by Doorlist's owner on 24 November 2026 ("Ok let's do all those
things") after Doorlist's capacity runs (Doorlist spec 0124, `docs/specs/dependencies.md`).

## Problem

`JobWorker` claims, once a poll, up to each kind's free room. A kind whose jobs come due some time after they are
enqueued is never woken by `LISTEN`, so it drains at most `concurrency` jobs a poll however many are due. Doorlist's
`payment.reconcile` (concurrency 1, 1 s poll) held the whole app to 38 requests a second on every database size, and
at a 5 s poll to 11. Doorlist works around it with a short poll and a higher concurrency.

## Not doing

- One `exists` query before the per-kind claims when idle (the other half of the Doorlist note): a new cross-kind
  query in `Queue` that has to fit rate buckets. Its own spec if wanted.
- Any change to `Queue.claim`, leases, rate buckets, the per-tenant cap or the schema.

## Shape

No API change. Behaviour:

- A claim that takes all of a kind's free room marks the kind **full**.
- When a run of a full kind ends, the worker wakes and claims again **for that kind alone**.
- A claim that takes less than the room ends the chain until the next poll (nothing due, empty bucket, tenant cap).
- Every kind, and the unhandled kinds, are still claimed for at least once a poll interval, however often refills
  wake the worker.

## Why this shape

Claiming again only for the kind that filled keeps the extra queries to one per finished run of a busy kind, and none
when idle. The alternative, a shorter poll for everyone, costs queries when idle on every kind (Doorlist measured
~45 claim queries a poll). The once-a-poll floor stops a busy kind's refills starving the others.

## Stack

- [x] **`claude/jobs-claim-refill`** — refill on run end, the once-a-poll floor, two `JobWorkerSuite` tests.
      Done when: 20 due jobs of a concurrency-1 kind drain with one poll; another kind due while a busy kind
      refills runs within a poll.

## Acceptance

```bash
./gradlew :surefold-jobs:build :surefold-jobs-otel:build :surefold-jobs-tenancy:build :surefold-app-jobs:build
```

## Open questions

1. **Version.** Recommended: publish through `pages.yml` as `0.18.1-jobs-g<sha>` for Doorlist (snapshots only, no
   Central release).
2. **Metric.** Recommended: none now; Doorlist's job-lag alarm already shows whether it drains.
