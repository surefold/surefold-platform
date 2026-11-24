# 0004 — A key view use that does not queue behind its neighbours

## Problem

Doorlist adopted retained key views (core spec 0265) on a trial branch. Its tests pass, but cached sealing got slower:
with eight callers on one tenant's key, p99 seal/open/index went from 84.8/3.7/3.9 µs to 200.9/203.8/260.1 µs, and
single-caller index went from 4.1 to 20.0 µs against a 10 µs budget. Doorlist task P04-CACHE-VIEWS is blocked on it,
and ten launch tasks sit behind that.

Reading the code, one `KeyView.using` makes four updates to shared state before and after the callback:

- `KeyCallers.admit`: one `modify` on the cache-wide caller cell. It appends a ticket to a list and filters finished
  tickets with a nested scan, so the update gets longer as more callers are in flight.
- `OwnedKeyMaterial.use`: a `modify` to register the use, a second `modify` to publish its working copy, and a third
  to remove it. Each runs in its own `resourceScope` and peeks the state again afterwards.

Each of those is a committed `transact` step, and every committed step swaps the one commit count all steps share
(core `Transact.kt`). So callers collide on that count as well as on the two cells, and a step that loses ten runs
then runs alone while every other commit waits. This is read from the source, not measured: no test or benchmark
here counts or times a use.

## Not doing

No change to what spec 0265 promises: operation-owned working bytes wiped on every exit, fixed `Denied` on revoke,
fixed `Unavailable` on expiry, `closeAndJoin` waiting for admitted callbacks, revoke zeroing copies in use.
Nothing to `lease`, load groups, retention, the ledger, legacy `get`, or any public signature.
No batching API that lends one copy to several operations; Doorlist keeps one `using` per operation.

## Shape

No API change. The call stays:

```kotlin
view.using { key -> seal(key, plain) }
```

What changes is the work behind it: at most two shared updates per use, each constant size.

- `OwnedKeyMaterial.use` copies the source first, then registers token and copy in one `modify`. A refused
  registration wipes the copy and answers `Denied`. One more update removes the token on exit.
- `KeyCallers` keeps a count of calls in flight in place of the ticket list. Admission adds one unless closed; the
  call's exit takes one away. `closeAndAwait` already polls: it waits for the count to reach zero.

## Why this shape

Copying before admission is safe because the source is zeroed only after the state says revoked or zeroed, and an
admission that commits proves neither was set. The alternative, keeping two updates so no copy is ever made for a
refused use, saves a wipe on the rare path and costs a contended update on every use; not recommended.

A count loses the per-ticket record `Closing.observe` reads today. The alternative is to keep tickets but hold them
in a structure with constant-time add and remove. Recommended: the count, if open question 2 confirms nothing reads
a ticket's identity.

## Stack

- [ ] **`key-view-use-counted`** — a suite that counts committed steps per `using` from `surefold.transact.runs`,
      and a `bench/` run of one and eight same-key callers.
      Done when: the suite fails at a ceiling below today's count, is committed with the ceiling at today's count,
      and the bench prints p50/p99 for both caller counts.
- [ ] **`key-view-use-one-registration`** — `OwnedKeyMaterial.use` registers once.
      Done when: the ceiling drops by one and `KeyViewSuite`, `RetainedKeyCacheSuite` pass.
- [ ] **`key-callers-in-flight-count`** — `KeyCallers` counts calls in flight.
      Done when: the ceiling is two constant-size steps per use and `KeyCachePublicCloseSuite` passes.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
./gradlew :surefold-tenancy-crypto-bench:run   # eight-caller p99 no worse than twice the one-caller p99
```

Then Doorlist re-pins the snapshot and reruns its paired cipher benchmark against 20/20/10 µs.

## Open questions

Answered by the owner on 24 November 2026: every recommendation taken.

1. Is two steps per use enough for Doorlist's budget, or must the caller cell leave the use path altogether?
   **Build entry 1 first and decide from its numbers.** Since every step swaps the shared commit count, expect the
   answer to be that two is not enough; that becomes a further entry here, not a wider entry 3.
2. Does anything depend on `KeyCallers` holding one ticket per call, beyond waiting for all of them to finish?
   Only `Closing.observe` and `reap` read them. **Treat the count as equivalent, and say so in the PR.**
3. Where does the benchmark live? **A `bench/surefold-tenancy-crypto-bench` module, as `surefold-jobs-load` does.**
4. Exact number of steps or a ceiling? **A ceiling, so a later improvement does not fail it.**
5. Is trimming the nested `resourceScope`s in scope? **Yes, inside entry 2, if entry 1 shows they matter.**
