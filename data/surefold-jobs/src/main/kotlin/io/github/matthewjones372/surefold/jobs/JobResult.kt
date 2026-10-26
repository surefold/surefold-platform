package io.github.matthewjones372.surefold.jobs

import java.time.Instant
import java.util.UUID
import kotlin.time.Duration

/** What a run of a job decided. */
sealed interface JobResult {
    /** The job is finished. */
    data object Done : JobResult

    /** Run it again after [after]. Asked for by the handler, so it does not use up an attempt. */
    data class Retry(val after: Duration, val reason: String) : JobResult

    /** The job no longer needs doing. */
    data class Skip(val reason: String) : JobResult

    /** The job cannot be done, and is not tried again. */
    data class Fail(val reason: String) : JobResult
}

/** One run of a job, as its handler sees it. [attempt] counts from one. */
class Job<P>(
    val id: UUID,
    val kind: JobKind<P>,
    val payload: P,
    val tenant: String?,
    val runAt: Instant,
    val attempt: Int,
)

/** Runs one job of a kind. Delivery is at least once: a job can run again after a crash, so this must be idempotent. */
fun interface JobHandler<P> {
    fun run(job: Job<P>): JobResult
}

/** A [JobKind] with the handler that runs it. */
class Handled<P> internal constructor(val kind: JobKind<P>, internal val handler: JobHandler<P>)

/** The handlers a worker runs, at most one per kind. */
class JobHandlers private constructor(internal val byName: Map<String, Handled<*>>) {
    companion object {
        fun of(vararg handled: Handled<*>): JobHandlers {
            val twice = handled.groupBy { it.kind.name }.filterValues { it.size > 1 }.keys
            require(twice.isEmpty()) { "Each job kind takes one handler, but these have more: $twice" }
            return JobHandlers(handled.associateBy { it.kind.name })
        }
    }
}
