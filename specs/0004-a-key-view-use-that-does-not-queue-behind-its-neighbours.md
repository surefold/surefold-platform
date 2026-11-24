# 0004 — A key view use that does not queue behind its neighbours

**Amended 24 November 2026, accepted by the owner the same day.** Entries 1 and 2 shipped and were measured. They
are not enough, and entry 3 as first written was wrong. This amendment replaces entry 3 and adds entry 4.

## Problem

Doorlist adopted retained key views (core spec 0265) on a trial branch. Its tests pass, but sealing got slower, and
Doorlist task P04-CACHE-VIEWS is blocked on it with ten launch tasks behind it.

One `KeyView.using` commits `transact` steps, and every committed step swaps the one commit count all steps share
(core `Transact.kt`). A step that loses ten runs then runs alone while every other commit waits. So callers of
unrelated keys queue behind one another.

## Measured

`KeyViewUseCostSuite` (entry 1) counts the steps: four on the first build, three since entry 2.

Doorlist's `CipherBench`, changed locally to seal through generation-pinned rings so each seal uses a view, on an
eight-core machine under heavy unrelated load. p50 is given because it held steady across runs; p99 did not.

| Seal | No view | View, four steps | View, three steps |
|---|---|---|---|
| 1 thread | 2.2 µs | 5.5 to 6.5 µs | 4.5 to 5.7 µs |
| 8 threads, one owner | 2.3 µs | 11.3 to 11.5 µs | 9.0 to 11.7 µs |
| 8 threads, eight owners | 2.2 µs | did not finish | 8.0 to 8.8 µs |

Doorlist's budget is p99 at most 20 µs. With the typical seal near 10 µs at eight callers, shedding one step at a
time will not meet it. Eight owners slowing each other confirms the shared commit count as the cause.

## Not doing

No change to what spec 0265 promises: operation-owned working bytes wiped on every exit, fixed `Denied` on revoke,
fixed `Unavailable` on expiry, `closeAndJoin` waiting for admitted callbacks, revoke zeroing copies in use.
Nothing to `lease`, load groups, retention, the ledger, legacy `get`, or any public signature.
No batching API that lends one copy to several operations.
Borrowing or closing a view, forgetting and revoking stay committed steps: they are rare.

## Shape

No API change. An ordinary use commits no step at all.

```kotlin
view.using { key -> seal(key, plain) }
```

Both places that commit today move their per-use bookkeeping out of `Shared`, onto JDK atomics, in the same order
each time: **write that I am here, then read whether I may be.** The other side does the mirror: **write that it is
over, then read who is still here.** Whichever goes second sees the other.

- `KeyCallers`: a count of calls in flight. A call adds one, then reads `closed`; closed means take one away and
  refuse. `closeAndAwait` sets `closed`, then waits, as it polls today, for the count to reach zero.
- `OwnedKeyMaterial.use`: add one, copy the source, put the copy in a concurrent map, then read the state. Revoked,
  zeroed or a closed view means wipe, remove and answer `Denied`. Revoke commits its state as today, then wipes
  every copy in the map. The last use out, on finding the material forgotten with no views, runs the terminal wipe:
  the one case where a use commits a step.

## Why this shape

It removes the cause, for every consumer, without an API change. The alternative is a scoped hold, one borrow for a
whole transaction, so three steps are paid once per transaction and not once per field. It is less delicate, but it
adds API, keeps working bytes alive for the length of a transaction, and leaves the queue in place for short
transactions. Not recommended, and "Not doing" already rules out a lending API.

The cost of the recommended shape is that two state machines proved under `transact` become hand-ordered atomic
code. `HandoffDecision` in core is already an `AtomicReference`, and Doorlist keeps two JDK atomics in its cipher for
this same p99 by an owner decision of 4 November 2026.

## Stack

- [x] **`key-view-use-counted`** — counts steps per use; adds the bench. surefold-platform#29.
- [x] **`key-view-use-one-registration`** — a use registers once; four steps became three. surefold-platform#30.
- [ ] **`key-callers-atomic-count`** — `KeyCallers` admits by an atomic count; no committed step.
      Done when: the ceiling is two, a story shows a call that arrives during close is refused or waited for and
      never neither, and every caller story is kept, rewritten or removed with its reason in the PR.
- [ ] **`key-material-uses-outside-steps`** — `OwnedKeyMaterial` tracks uses in atomics.
      Done when: the ceiling is zero for an ordinary use, a story shows revoke during a use wipes its copy and the
      use answers `Denied`, and a story shows the last use out of forgotten material wipes the source once.

Withdrawn: `key-callers-in-flight-count`. A count kept in `Shared` needs a step to add and a step to take away, so it
would have raised the count from three to four. Admission costs one step today and finishing costs none.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
./gradlew :surefold-tenancy-crypto-bench:run
```

Then Doorlist pins the published build and runs its cipher benchmark through pinned rings, on a quiet machine,
against p99 20 µs for seal and open and 10 µs for a blind index.

## Open questions

Questions 1 to 5 of the first draft were answered by the owner on 24 November 2026 and are in git history. The five
below were answered the same day: every recommendation taken.

1. **JDK atomics in this module's main code.** Yes, in these two classes only, each with a one-line comment naming
   this spec.
2. **Proving the two races.** `takingTurns` chooses who runs only where a surefold thread waits or wakes, not between
   two atomic operations. Each story drives the race from inside the callback or the loader, where the test already
   holds control, and one stress story per race runs many times. If an entry cannot pin a race down that way, it
   stops and says so; a seam in core is then its own spec.
3. **The caller stories built on admission being a step.** About twelve of the thirty-three fault or interrupt that
   step. Keep the guarantee where one survives (closed refuses, close waits, interruption restored) and remove
   stories about the step's own instrumentation, each named in the PR.
4. **Revoke wiping copies in use.** Kept, as spec 0265 promises it; hence the map and not a bare count.
5. **Order of entries.** Callers first. It is the smaller change and lets the bench show what one step is worth
   before the larger one.
