# 0001 — Threads and latches a build failure

## Problem

`AGENTS.md` now asks for surefold's primitives over the JDK's, but nothing enforces it. Core enforces part of it:
its spec 0309 made `Thread.ofVirtual`, `Thread.ofPlatform` and `Thread(..)` in main sources a detekt failure, with
every remaining use listed with its reason. This repository's `detekt.yml` has no such rule, and its main sources
use the JDK's primitives in 8 files:

- **Data modules:** `surefold-jobs`' `JobWorker`, `surefold-postgres-notify`'s `PostgresNotifications` and
  `surefold-tenancy-crypto`'s `KeyCache`.
- **`surefold-bank`:** `Api`, `BankNode`, `Main`, `Sse` and `Train`.

Tests use them more: 12 files start threads, 10 use latches, 3 use blocking queues and 5 sleep.

`surefold-bank` is the example people copy, so a thread or latch in it gets reproduced in their services.

## Not doing

- **A driver's own threads.** JDBC or the Postgres driver's listener thread isn't started by our code. Only
  code we write is in scope.
- **Atomics and `ConcurrentHashMap`.** Core's 0309 and 0312 both leave them out (hot paths).
- **Test sources, in this spec.** Question 1.

## Shape

A second instance of the rule beside `ForbiddenMethodCall/runCatching`, for main sources, as core's
`ForbiddenMethodCall/threads`:

```yaml
ForbiddenMethodCall/threads:
  active: true
  methods:
    - reason: 'A thread started by hand belongs to no scope: fork with scope.async (AGENTS.md).'
      value: 'java.lang.Thread.ofVirtual'          # and ofPlatform, <init>, startVirtualThread,
                                                    # kotlin.concurrent.thread, Executors.new*
    - reason: 'A latch hands over one value: use a Promise (AGENTS.md).'
      value: 'java.util.concurrent.CountDownLatch.<init>'   # and CyclicBarrier, Phaser
    - reason: 'Shared state is a Shared, changed in a transaction (AGENTS.md).'
      value: 'kotlin.synchronized'                 # and ReentrantLock, ReentrantReadWriteLock
    - reason: 'Values between forks go through a channel (AGENTS.md).'
      value: 'java.util.concurrent.LinkedBlockingQueue.<init>'  # and ArrayBlockingQueue, SynchronousQueue
  excludes:
    - '**/test/**'
    - # each remaining main file, with its reason, until the entries below remove it
```

Unlike the existing `ForbiddenMethodCall` instance, this one does not exclude `**/example/**`. `surefold-bank`
is held to it.

## Why this shape

A rule first, listing every current use, makes the rest a list that only shrinks, as 0309 did in core.
**Alternative:** fix every file first, then add the rule. That's a larger first PR, and new uses keep arriving
while it is reviewed. Recommended: rule first.

## Stack

- [ ] **`spec-0001-rule`**: the rule, with the eight main files listed in `excludes` with their reasons.
      Done when: a new `CountDownLatch` in a main source, `surefold-bank`'s included, fails `./gradlew detekt`
      until its file is listed.
- [ ] **`spec-0001-bank`**: `surefold-bank`'s five files onto `scope`/`async`, `Promise`, `Shared` and
      `channel`.
      Done when: the rule lists none of them, and the bank's tests pass unchanged.
- [ ] **`spec-0001-data`**: `JobWorker`, `PostgresNotifications` and `KeyCache`.
      Done when: the same, for those files.

## Acceptance

```bash
./gradlew detekt
grep -n -A30 "ForbiddenMethodCall/threads" config/detekt/detekt.yml
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **Test sources too?** Agents copy tests most, and 20 test files use these. Recommended: a later spec, after
   core's 0307 settles the test patterns.
2. **Bank before data, or after?** The bank is copied most; the data modules are run most. Recommended: bank first.
3. **`PostgresNotifications` and the driver's `LISTEN` loop.** If the driver needs a blocking thread for the
   loop, a forked `scope` is still the owner. Recommended: move it, unless the driver forbids that, then list it.
4. **`Executors.new*` in one line or each factory?** detekt matches exact names. Recommended: each factory by
   name. The scheduled ones are core 0132's compiler warning.
