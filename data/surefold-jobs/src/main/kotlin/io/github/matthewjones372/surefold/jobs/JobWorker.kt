package io.github.matthewjones372.surefold.jobs

import io.github.matthewjones372.surefold.clock
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
import javax.sql.DataSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
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

    @Volatile
    private var stopping = false

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
        while (!stopping) {
            try {
                if (clock.get().now() >= housekeepingDue) {
                    queue.sweep()
                    queue.prune(settings.retention)
                    housekeepingDue = clock.get().now() + settings.housekeeping.toJavaDuration()
                }
                claimAndRun()
            } catch (failed: SQLException) {
                logWarn("surefold-jobs: worker $name could not reach the jobs table, and will try again: $failed")
            }
            if (!stopping) clock.get().sleep(settings.poll)
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
        if (stopping) return
        stopping = true
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
    }
}
