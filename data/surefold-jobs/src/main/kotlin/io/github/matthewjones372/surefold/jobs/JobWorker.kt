package io.github.matthewjones372.surefold.jobs

import io.github.matthewjones372.surefold.clock
import io.github.matthewjones372.surefold.gauge
import io.github.matthewjones372.surefold.logInfo
import io.github.matthewjones372.surefold.logWarn
import io.github.matthewjones372.surefold.surefoldFuture
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import javax.sql.DataSource
import kotlin.concurrent.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import kotlin.time.toJavaDuration

/**
 * How a [JobWorker] runs. A run's lease is its kind's deadline plus [leaseMargin], so a worker that is slow to write
 * its result back is not mistaken for a dead one.
 */
data class JobSettings(
    val poll: Duration = 1.seconds,
    val leaseMargin: Duration = 30.seconds,
    val drain: Duration = 30.seconds,
    val housekeeping: Duration = 1.minutes,
    val retention: Duration = 7.days,
)

/**
 * Claims due jobs every [JobSettings.poll], up to each kind's concurrency, and runs each on a virtual thread with the
 * bindings [start] was called with. Every [JobSettings.housekeeping] it sweeps expired leases and prunes history.
 *
 * [close] stops claiming and waits up to [JobSettings.drain] for runs in flight, then interrupts the rest: their
 * leases expire and another worker runs them again, which is why a handler must be idempotent.
 */
class JobWorker(
    source: DataSource,
    private val handlers: JobHandlers,
    private val settings: JobSettings = JobSettings(),
    val name: String = "${ProcessHandle.current().pid()}-${UUID.randomUUID()}",
) : AutoCloseable {

    private val queue = Queue(source, name)
    private val run = JobRun(queue, handlers)
    private val running = handlers.byName.keys.associateWith { AtomicInteger() }
    private val inFlight = ConcurrentHashMap.newKeySet<CompletableFuture<*>>()

    // The loop sleeps between polls; close interrupts it only while it sleeps, never in the middle of a query.
    private val lock = ReentrantLock()
    private var stopping = false
    private var sleeping: Thread? = null

    @Volatile
    private var loop: CompletableFuture<Unit>? = null

    /** Starts polling, and answers this worker. */
    fun start(): JobWorker = apply {
        check(loop == null) { "job worker $name has already started" }
        logInfo("surefold-jobs: worker $name started, handling ${handlers.byName.keys.sorted()}")
        loop = surefoldFuture { polling() }
    }

    private fun polling() {
        var housekeepingDue = clock.get().now()
        while (true) {
            try {
                if (clock.get().now() >= housekeepingDue) {
                    queue.sweep()
                    queue.prune(settings.retention)
                    measureBacklog()
                    housekeepingDue = clock.get().now() + settings.housekeeping.toJavaDuration()
                }
                claimAndRun()
            } catch (failed: SQLException) {
                logWarn("surefold-jobs: worker $name could not reach the jobs table, and will try again: $failed")
            }
            if (!pause()) return
        }
    }

    /** Sleeps until the next poll, and answers false once the worker is closing. */
    private fun pause(): Boolean {
        lock.withLock {
            if (stopping) return false
            sleeping = Thread.currentThread()
        }
        try {
            clock.get().sleep(settings.poll)
        } catch (_: InterruptedException) {
            // Woken by close.
        } finally {
            lock.withLock { sleeping = null }
            Thread.interrupted()
        }
        return lock.withLock { !stopping }
    }

    /** Sets each handled kind's depth and lag, zero for one with nothing due, so a drained queue reads as drained. */
    internal fun measureBacklog() {
        val backlog = queue.backlog()
        (handlers.byName.keys + backlog.keys).forEach { kind ->
            val (depth, lag) = backlog[kind] ?: Backlog(0, ZERO)
            gauge(DEPTH, "kind" to kind).set(depth.toDouble())
            gauge(LAG, "kind" to kind).set(lag.toDouble(DurationUnit.SECONDS))
        }
    }

    /** Claims what each kind has room for, and the jobs of kinds nothing here handles, and runs them. */
    internal fun claimAndRun(): Int {
        val claimed = handlers.byName.values.flatMap { handled ->
            val room = handled.kind.concurrency - running.getValue(handled.kind.name).get()
            if (room <= 0) emptyList() else queue.claim(room, lease(handled.kind.deadline), Kinds.Only(handled.kind))
        } + queue.claim(UNHANDLED_BATCH, lease(ZERO), Kinds.Except(handlers.byName.keys))
        claimed.forEach(::dispatch)
        return claimed.size
    }

    private fun dispatch(job: Claimed) {
        val count = running[job.kind]
        count?.incrementAndGet()
        val future = surefoldFuture {
            try {
                run.run(job)
            } catch (failed: SQLException) {
                logWarn("surefold-jobs: job ${job.id} ran, but its result could not be written: $failed")
            } finally {
                count?.decrementAndGet()
            }
        }
        inFlight += future
        // Runs at once if the job has already finished, so nothing is left in the set.
        future.whenComplete { _, _ -> inFlight -= future }
    }

    private fun lease(deadline: Duration) = deadline + settings.leaseMargin

    /** Stops claiming, waits up to [JobSettings.drain] for runs in flight, and interrupts what is left. */
    override fun close() {
        lock.withLock {
            if (stopping) return
            stopping = true
            sleeping?.interrupt()
        }
        loop?.join()
        val until = System.nanoTime() + settings.drain.inWholeNanoseconds
        inFlight.toList().forEach { future ->
            try {
                future.get(maxOf(0, until - System.nanoTime()), TimeUnit.NANOSECONDS)
            } catch (_: TimeoutException) {
                future.cancel(true)
            } catch (_: ExecutionException) {
                // A run's own failures are written as its job's result; anything else was logged where it ran.
            } catch (_: CancellationException) {
                // Cancelled by an earlier pass of this loop.
            }
        }
        logInfo("surefold-jobs: worker $name stopped")
    }

    private companion object {
        const val UNHANDLED_BATCH = 10

        /** Jobs due and waiting, per kind. */
        const val DEPTH = "surefold.jobs.depth"

        /** How long the oldest job due has waited past its run time, in seconds, per kind. */
        const val LAG = "surefold.jobs.lag.seconds"
    }
}
