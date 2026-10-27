package io.github.matthewjones372.surefold.idempotency

import io.github.matthewjones372.surefold.jobs.Cron
import io.github.matthewjones372.surefold.jobs.CronEntry
import io.github.matthewjones372.surefold.jobs.Handled
import io.github.matthewjones372.surefold.jobs.JobCodec
import io.github.matthewjones372.surefold.jobs.JobKind
import io.github.matthewjones372.surefold.jobs.JobResult

private object NoPayload : JobCodec<Unit> {
    override val version = 1

    override fun encode(payload: Unit) = "{}"

    override fun decode(version: Int, json: String) = Unit
}

/**
 * The job that deletes expired idempotency keys (spec 0226), run on a timetable by a `surefold-jobs` worker. Give the
 * worker [handledBy] a store and [every] a cron; any number of instances prune each firing once.
 */
object IdempotencyPrune : JobKind<Unit>("surefold-idempotency-prune", NoPayload) {

    /** This kind, run by pruning [store]. */
    fun handledBy(store: PostgresIdempotency): Handled<Unit> = handledBy { _ ->
        store.prune()
        JobResult.Done
    }

    /** A cron entry that prunes at every firing of [cron]. */
    fun every(cron: Cron): CronEntry<Unit> = CronEntry(name, cron, this, Unit)
}
