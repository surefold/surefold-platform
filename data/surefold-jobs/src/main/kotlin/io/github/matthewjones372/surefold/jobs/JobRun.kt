package io.github.matthewjones372.surefold.jobs

import io.github.matthewjones372.surefold.Carriers
import io.github.matthewjones372.surefold.Schedule
import io.github.matthewjones372.surefold.clock
import io.github.matthewjones372.surefold.counter
import io.github.matthewjones372.surefold.fail.nonFatalOrThrow
import io.github.matthewjones372.surefold.increment
import io.github.matthewjones372.surefold.logWarn
import io.github.matthewjones372.surefold.timed
import io.github.matthewjones372.surefold.timeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/** Runs, tagged by kind and by result: `done`, `retry`, `continue`, `skipped` or `failed`. */
internal const val RUNS = "surefold.jobs.runs"

/** How long each handler ran, in seconds, tagged by kind. */
internal const val DURATION = "surefold.jobs.duration.seconds"

/** Doubling from a second, each delay somewhere between half and one and a half times as long. */
internal val defaultBackoff: Schedule<Unit, Any?> = Schedule.exponential<Unit>(1.seconds).jittered(0.5, 1.5)

/**
 * Runs claimed jobs through [handlers] and writes what each decided back through [queue]. A job past its expiry is
 * skipped without a run. One of a kind nothing handles, or with a payload its codec cannot read, fails at once. A
 * run that throws or misses its deadline is retried after [backoff]'s delay for its attempt, and fails on its last.
 */
internal class JobRun(
    private val queue: Queue,
    private val handlers: JobHandlers,
    private val backoff: Schedule<Unit, Any?> = defaultBackoff,
) {

    fun run(claimed: Claimed) {
        val now = clock.get().now()
        val handled = handlers.byName[claimed.kind]
        when {
            claimed.expiresAt != null && now > claimed.expiresAt -> {
                queue.finish(claimed, Finished.Skipped, "expired at ${claimed.expiresAt}")
                counted(claimed, "skipped")
            }

            handled == null -> failed(claimed, "no handler for kind \"${claimed.kind}\"")

            else -> timed(DURATION, "kind" to claimed.kind) { run(claimed, handled) }
        }
    }

    private fun <P> run(claimed: Claimed, handled: Handled<P>) {
        val job = try {
            decoded(claimed, handled.kind)
        } catch (thrown: Exception) {
            failed(claimed, "its payload could not be read: ${thrown.nonFatalOrThrow()}")
            return
        }
        val result = try {
            timeoutOrNull(handled.kind.deadline) {
                Carriers.within(Carried.read(claimed.carried)) { handled.handler.run(job) }
            } ?: return retried(claimed, "no answer within ${handled.kind.deadline}")
        } catch (thrown: Exception) {
            return retried(claimed, "it threw ${thrown.nonFatalOrThrow()}")
        }
        when (result) {
            JobResult.Done -> {
                queue.finish(claimed, Finished.Done)
                counted(claimed, "done")
            }

            is JobResult.Skip -> {
                queue.finish(claimed, Finished.Skipped, result.reason)
                counted(claimed, "skipped")
            }

            is JobResult.Fail -> failed(claimed, result.reason)

            is JobResult.Retry -> {
                queue.retry(claimed, clock.get().now() + result.after.toJavaDuration(), result.reason, count = false)
                counted(claimed, "retry")
            }

            is JobResult.Continue<*> -> {
                // A handler of P answers a Continue of P: the handler's type says so, and erasure cannot.
                @Suppress("UNCHECKED_CAST")
                val next = handled.kind.codec.encode(result.payload as P)
                queue.proceed(claimed, next, clock.get().now() + result.after.toJavaDuration(), result.progress)
                counted(claimed, "continue")
            }
        }
    }

    private fun <P> decoded(claimed: Claimed, kind: JobKind<P>) = Job(
        id = claimed.id,
        kind = kind,
        payload = kind.codec.decode(claimed.payloadVersion, claimed.payload),
        tenant = claimed.tenant,
        runAt = claimed.runAt,
        attempt = claimed.attempts,
        progress = claimed.progress,
    )

    private fun retried(claimed: Claimed, error: String) {
        val delay = if (claimed.attempts < claimed.maxAttempts) delayAfter(claimed.attempts) else null
        if (delay == null) {
            failed(claimed, "$error, on attempt ${claimed.attempts} of ${claimed.maxAttempts}")
        } else {
            queue.retry(claimed, clock.get().now() + delay.toJavaDuration(), error, count = true)
            counted(claimed, "retry")
        }
    }

    private fun failed(claimed: Claimed, error: String) {
        logWarn("surefold-jobs: job ${claimed.id} of kind \"${claimed.kind}\" failed: $error")
        queue.finish(claimed, Finished.Failed, error)
        counted(claimed, "failed")
    }

    private fun counted(claimed: Claimed, result: String) =
        counter(RUNS, "kind" to claimed.kind, "result" to result).increment()

    /** [backoff]'s delay before the attempt after [attempt], or null when it has given up. */
    private fun delayAfter(attempt: Int): Duration? {
        var step = backoff.step
        repeat(attempt - 1) {
            step = (step(Unit) as? Schedule.Decision.Continue)?.step ?: return null
        }
        return (step(Unit) as? Schedule.Decision.Continue)?.delay
    }
}
