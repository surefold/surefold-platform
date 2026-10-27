package io.github.matthewjones372.surefold.jobs

import io.github.matthewjones372.surefold.clock
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import javax.sql.DataSource
import kotlin.time.Duration
import kotlin.time.toJavaDuration

/** A job one worker holds the lease on, as claimed. [attempts] counts this run, and fences what the run writes. */
internal data class Claimed(
    val id: UUID,
    val tenant: String?,
    val kind: String,
    val payload: String,
    val payloadVersion: Int,
    val carried: String?,
    val runAt: Instant,
    val expiresAt: Instant?,
    val priority: Int,
    val attempts: Int,
    val maxAttempts: Int,
)

/** Which kinds a claim takes. */
internal sealed interface Kinds {
    data object All : Kinds

    data class Only(val kind: JobKind<*>) : Kinds

    /** Every kind but these: the ones no handler here runs. */
    data class Except(val names: Set<String>) : Kinds
}

/** How a run left its job, as history records it. */
internal enum class Finished(val status: String) { Done("done"), Failed("failed"), Skipped("skipped") }

/**
 * The jobs table as [worker] sees it: what it claims, finishes and sweeps, each in a transaction of its own on
 * [source]. A finish names the attempt it ran, so a worker whose lease expired and was claimed again changes nothing.
 */
internal class Queue(private val source: DataSource, private val worker: String) {

    /**
     * Claims up to [limit] due jobs, highest priority and then earliest first, and holds each for [lease]. Rows
     * another worker is claiming are skipped rather than waited on.
     */
    fun claim(limit: Int, lease: Duration, kinds: Kinds = Kinds.All): List<Claimed> {
        val now = clock.get().now()
        val (filter, names) = when (kinds) {
            Kinds.All -> "" to emptyList()
            is Kinds.Only -> "and kind = ?" to listOf(kinds.kind.name)
            is Kinds.Except -> "and kind <> all(?)" to listOf(kinds.names.toList())
        }
        return source.connection.use { connection ->
            connection.prepareStatement(claiming(filter)).use { statement ->
                statement.bind(listOf(now + lease.toJavaDuration(), worker, now, now) + names + limit)
                statement.executeQuery().use { rows ->
                    generateSequence { if (rows.next()) rows.claimed() else null }.toList()
                }
            }
        }.sortedWith(compareByDescending<Claimed> { it.priority }.thenBy { it.runAt })
    }

    /** Moves [job] to history as [how], with [error] if given. Answers false when this worker no longer holds it. */
    fun finish(job: Claimed, how: Finished, error: String? = null): Boolean {
        val now = clock.get().now()
        return source.connection.use { connection ->
            connection.prepareStatement(finishing(how)).use { statement ->
                statement.bind(listOf(job.id, job.attempts, worker, error, now, now))
                statement.executeUpdate() == 1
            }
        }
    }

    /**
     * Returns [job] to pending, to run at [at] with [error] noted. A retry the handler did not [count] gives its
     * attempt back. Answers false when this worker no longer holds it.
     */
    fun retry(job: Claimed, at: Instant, error: String, count: Boolean): Boolean {
        val now = clock.get().now()
        return source.connection.use { connection ->
            connection.prepareStatement(RETRY).use { statement ->
                statement.bind(listOf(at, error, if (count) 0 else 1, now, job.id, job.attempts, worker))
                statement.executeUpdate() == 1
            }
        }
    }

    /**
     * Returns each running job whose lease has passed to pending, as a worker that died mid-run left it, and fails into
     * history those that have had every attempt. Answers how many it returned.
     */
    fun sweep(): Int {
        val now = clock.get().now()
        return source.connection.use { connection ->
            connection.prepareStatement(sweeping).use { statement ->
                statement.bind(listOf(now, "its lease expired on its last attempt", now, now, now))
                statement.executeUpdate()
            }
        }
    }

    /** Deletes history that finished more than [retention] ago, and answers how many rows. */
    fun prune(retention: Duration): Int = source.connection.use { connection ->
        connection.prepareStatement("delete from surefold_jobs_history where finished_at < ?").use { statement ->
            statement.bind(listOf(clock.get().now() - retention.toJavaDuration()))
            statement.executeUpdate()
        }
    }

    private fun ResultSet.claimed() = Claimed(
        id = getObject("id", UUID::class.java),
        tenant = getString("tenant_id"),
        kind = getString("kind"),
        payload = getString("payload"),
        payloadVersion = getInt("payload_version"),
        carried = getString("carried"),
        runAt = getObject("run_at", OffsetDateTime::class.java).toInstant(),
        expiresAt = instant("expires_at"),
        priority = getInt("priority"),
        attempts = getInt("attempts"),
        maxAttempts = getInt("max_attempts"),
    )

    private fun ResultSet.instant(column: String): Instant? = getObject(column, OffsetDateTime::class.java)?.toInstant()

    private companion object {
        fun claiming(filter: String) = """
            update surefold_jobs
            set status = 'running', locked_until = ?, locked_by = ?, attempts = attempts + 1, updated_at = ?
            where id in (
                select id from surefold_jobs where status = 'pending' and run_at <= ? $filter
                order by priority desc, run_at limit ? for update skip locked
            )
            returning id, tenant_id, kind, payload::text, payload_version, carried::text, run_at, expires_at, priority,
                attempts, max_attempts
        """

        const val RETRY = """
            update surefold_jobs set status = 'pending', run_at = ?, last_error = ?, attempts = attempts - ?,
                locked_until = null, locked_by = null, updated_at = ?
            where id = ? and attempts = ? and locked_by = ? and status = 'running'
        """

        fun finishing(how: Finished) = """
            with gone as (
                delete from surefold_jobs where id = ? and attempts = ? and locked_by = ? and status = 'running'
                returning *
            )
            ${historyOf("gone", how.status)}
        """

        // One statement, so a job is either failed or returned, never both and never neither.
        val sweeping = """
            with expired as (
                select id from surefold_jobs where status = 'running' and locked_until < ? for update skip locked
            ),
            spent as (
                delete from surefold_jobs where id in (select id from expired) and attempts >= max_attempts
                returning *
            ),
            failed as (${historyOf("spent", Finished.Failed.status)} returning 1)
            update surefold_jobs set status = 'pending', locked_until = null, locked_by = null,
                last_error = 'its lease expired', updated_at = ?
            where id in (select id from expired) and attempts < max_attempts
        """
    }
}
