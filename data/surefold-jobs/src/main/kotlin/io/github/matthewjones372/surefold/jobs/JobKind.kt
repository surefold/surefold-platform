package io.github.matthewjones372.surefold.jobs

import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.minutes

/** How a payload is stored: as the JSON text [encode] writes, under the [version] it writes. */
interface JobCodec<P> {
    val version: Int

    fun encode(payload: P): String

    /** Reads what [encode] wrote under [version] or an earlier one, and throws for what it cannot. */
    fun decode(version: Int, json: String): P
}

/** A kind of job: its name in the table, how its payload is stored, and the bounds every run of it is held to. */
open class JobKind<P>(
    val name: String,
    val codec: JobCodec<P>,
    val deadline: Duration = 1.minutes,
    val concurrency: Int = 1,
) {
    init {
        require(name.isNotBlank()) { "A job kind needs a name." }
        require(deadline > ZERO) { "Job kind \"$name\" needs a positive deadline, not $deadline." }
        require(concurrency >= 1) { "Job kind \"$name\" must run at least one job at once, not $concurrency." }
    }

    /** This kind with [handler], for [JobHandlers.of]. */
    fun handledBy(handler: JobHandler<P>): Handled<P> = Handled(this, handler)

    override fun toString(): String = "JobKind($name)"
}
