# Jobs

`surefold-jobs` keeps work that has to happen later, or again until it works, in a Postgres table: a reminder to send
in an hour, a hold to release when it expires, a call to a provider that timed out. A job is enqueued in the same
transaction as the change that caused it, so both commit or neither does. Workers on any number of instances claim
due jobs without running one twice at the same time, and a worker that dies mid-run leaves a lease that expires and
is claimed again.

Delivery is **at least once**. A run can be repeated after a crash or an expired lease, so a handler must be
idempotent: check whether its effect already happened, or make the effect safe to repeat.

```kotlin
dependencies {
    implementation("io.github.matthewjones372:surefold-jobs:0.13.0")
    implementation("io.github.matthewjones372:surefold-app-jobs:0.13.0") // the worker as a graph node
}
```

## The tables

The tables ship as a Liquibase changelog in formatted SQL, `surefold/jobs/postgres.sql`, for the service's own
changelog to include. Nothing creates them at start. They need Postgres 15 or newer.

```yaml
databaseChangeLog:
  - include:
      file: surefold/jobs/postgres.sql
```

`surefold_jobs` holds every job not yet finished. A finished job moves to `surefold_jobs_history` in the transaction
that finishes it, and is deleted from there once it is older than `JobSettings.retention`.

## A kind, a handler and a worker

<!-- jobs -->
```kotlin
import io.github.matthewjones372.surefold.app.Module
import io.github.matthewjones372.surefold.app.liquibase.migrations
import io.github.matthewjones372.surefold.app.singleOf
import io.github.matthewjones372.surefold.clock
import io.github.matthewjones372.surefold.jobs.JobCodec
import io.github.matthewjones372.surefold.jobs.JobHandlers
import io.github.matthewjones372.surefold.jobs.JobKind
import io.github.matthewjones372.surefold.jobs.JobResult
import io.github.matthewjones372.surefold.jobs.JobSettings
import io.github.matthewjones372.surefold.jobs.Jobs
import java.sql.Connection
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

data class Reminder(val orderId: String)

object ReminderCodec : JobCodec<Reminder> {
    override val version = 1

    override fun encode(payload: Reminder) = """{"orderId": "${payload.orderId}"}"""

    override fun decode(version: Int, json: String) =
        Reminder(json.substringAfter("\"orderId\": \"").substringBefore('"'))
}

object SendReminder : JobKind<Reminder>("send-reminder", ReminderCodec, deadline = 30.seconds, concurrency = 4)

fun interface Mailer {
    fun remind(orderId: String): Boolean
}

/** Called inside the transaction that places the order, on its connection: both commit, or neither does. */
fun remindLater(connection: Connection, orderId: String) {
    Jobs.enqueue(
        connection,
        SendReminder,
        Reminder(orderId),
        dedupeKey = "reminder-$orderId",
        runAt = clock.get().now().plusSeconds(3_600),
    )
}

fun reminders(mailer: Mailer): Module =
    migrations("db/changelog.yaml") +
        singleOf { -> JobSettings() } +
        jobWorker(
            JobHandlers.of(
                SendReminder.handledBy { job ->
                    if (mailer.remind(job.payload.orderId)) JobResult.Done else JobResult.Retry(5.minutes, "busy")
                },
            ),
        )
```

- **A kind** names its jobs in the table and says how its payload is stored: its codec writes JSON text under a
  version, and reads that version or an earlier one. Its `deadline` bounds one run and its `concurrency` how many run
  at once on one worker.
- **Enqueueing** takes the caller's `Connection` and never commits. It is idempotent on the tenant, the kind and the
  dedupe key: a second enqueue with the same key, while the first is waiting or running, answers `false` and changes
  nothing. `Jobs.cancel` and `Jobs.reschedule` take the same key. A finished job frees its key.
- **A handler** answers `Done`, `Retry(after, reason)`, `Skip(reason)` or `Fail(reason)`. It runs under the context
  the enqueuing thread carried, so its log lines carry the same annotations and its trace continues the request's.
- **The worker** is a node after `Migrated`. Released, it stops claiming, waits up to `JobSettings.drain` for runs in
  flight, and interrupts what is left; those leases expire and another worker runs them again.

## A rate across every instance

A kind that calls an outside provider can carry the provider's limit:

```kotlin
object ChargeCard : JobKind<Charge>("charge-card", ChargeCodec, rate = JobRate(10, per = 1.seconds, burst = 20))
```

The bucket is a row in `surefold_jobs_buckets`, shared by every worker on every instance. A claim for the kind locks
the row, refills it from the clock, claims no more jobs than it holds whole tokens, and takes what it claimed, in one
transaction. A kind's `concurrency` still bounds how many run at once on one worker.

## Retries and failures

| What happened | What the job does |
|---|---|
| `Retry(after, reason)` | runs again after `after`, without using up an attempt |
| the handler threw, or missed its deadline | runs again after the backoff for that attempt, doubling from a second with jitter; fails on its last attempt (`maxAttempts`, five by default) |
| `Skip` | moves to history as `skipped` |
| `Fail` | moves to history as `failed`, with a warning logged |
| past its `expiresAt` when claimed | moves to history as `skipped`, without a run |
| a kind no handler runs, or a payload the codec cannot read | fails at once, whatever attempts are left |
| the worker died mid-run | its lease expires, and the job is pending again; on its last attempt it fails |

## Settings

`JobSettings` is a node, so it can be read from the service's configuration like any other section.

| Setting | Default | What it does |
|---|---|---|
| `poll` | 1s | how often the worker claims due jobs |
| `leaseMargin` | 30s | a run's lease is its kind's deadline plus this |
| `drain` | 30s | how long release waits for runs in flight |
| `housekeeping` | 1m | how often expired leases are swept, history pruned and the backlog measured |
| `retention` | 7d | how long finished jobs stay in history |
| `perTenant` | none | at most this many of one tenant's jobs in a claim, so one tenant's burst cannot hold the others behind it; jobs with no tenant count as one tenant |

## Metrics

| Metric | Kind | Tags |
|---|---|---|
| `surefold.jobs.runs` | counter | `kind`, `result`: `done`, `retry`, `skipped`, `failed` |
| `surefold.jobs.duration.seconds` | histogram | `kind` |
| `surefold.jobs.depth` | gauge, jobs due and waiting | `kind` |
| `surefold.jobs.lag.seconds` | gauge, how long the oldest due job has waited | `kind` |

## Time

Everything reads `clock`: what `enqueue` writes as the run time and the lease, what a claim counts as due, and how
long the worker sleeps between polls. A test that binds a `TestClock` moves all of it.
