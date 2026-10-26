package io.github.matthewjones372.surefold.jobs

import io.github.matthewjones372.surefold.Carriers
import io.github.matthewjones372.surefold.clock
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Types
import java.time.Instant
import java.time.ZoneOffset

/**
 * Enqueues, cancels and reschedules jobs on the caller's [Connection], so each commits or rolls back with the
 * caller's own transaction. Nothing here commits, and nothing opens a connection. Each is idempotent on a job's key:
 * its tenant, its kind and its dedupe key.
 */
object Jobs {

    /**
     * Enqueues [payload] as a job of [kind], with what this thread carries, its trace among it. Answers false, and
     * changes nothing, when a job with the same key is already waiting or running.
     */
    fun <P> enqueue(
        connection: Connection,
        kind: JobKind<P>,
        payload: P,
        dedupeKey: String,
        runAt: Instant = clock.get().now(),
        expiresAt: Instant? = null,
        priority: Int = 0,
        maxAttempts: Int = 5,
        tenant: String? = null,
    ): Boolean {
        require(maxAttempts >= 1) { "A job needs at least one attempt, not $maxAttempts." }
        val now = clock.get().now()
        return connection.prepareStatement(ENQUEUE).use { statement ->
            statement.bind(
                listOf(
                    uuidV7(now), tenant, kind.name, dedupeKey, kind.codec.encode(payload), kind.codec.version,
                    Carried.write(Carriers.capture()), runAt, expiresAt, priority, maxAttempts, now, now,
                ),
            )
            statement.executeUpdate() == 1
        }
    }

    /** Cancels the waiting job with this key into history. Answers false when none waits: a running one runs on. */
    fun cancel(connection: Connection, kind: JobKind<*>, dedupeKey: String, tenant: String? = null): Boolean {
        val now = clock.get().now()
        return connection.prepareStatement(cancelling(tenant)).use { statement ->
            statement.bind(key(kind, dedupeKey, tenant) + listOf(now, now))
            statement.executeUpdate() == 1
        }
    }

    /** Moves the waiting job with this key to [runAt]. Answers false when none is waiting. */
    fun reschedule(
        connection: Connection,
        kind: JobKind<*>,
        dedupeKey: String,
        runAt: Instant,
        tenant: String? = null,
    ): Boolean = connection.prepareStatement(rescheduling(tenant)).use { statement ->
        statement.bind(listOf(runAt, clock.get().now()) + key(kind, dedupeKey, tenant))
        statement.executeUpdate() == 1
    }

    private fun key(kind: JobKind<*>, dedupeKey: String, tenant: String?): List<Any> =
        listOfNotNull(kind.name, dedupeKey, tenant)

    private const val ENQUEUE = """
        insert into surefold_jobs (id, tenant_id, kind, dedupe_key, payload, payload_version, carried, run_at,
            expires_at, priority, status, max_attempts, created_at, updated_at)
        values (?, ?::text, ?, ?, ?::jsonb, ?, ?::jsonb, ?, ?::timestamptz, ?, 'pending', ?, ?, ?)
        on conflict on constraint surefold_jobs_key do nothing
    """

    // Two forms rather than `is not distinct from`, which no index serves.
    private fun keyed(tenant: String?) =
        "kind = ? and dedupe_key = ? and " + if (tenant == null) "tenant_id is null" else "tenant_id = ?"

    private fun cancelling(tenant: String?) = """
        with gone as (delete from surefold_jobs where status = 'pending' and ${keyed(tenant)} returning *)
        insert into surefold_jobs_history ($KEPT, status, updated_at, finished_at)
        select $KEPT, 'cancelled', ?, ? from gone
    """

    private fun rescheduling(tenant: String?) =
        "update surefold_jobs set run_at = ?, updated_at = ? where status = 'pending' and ${keyed(tenant)}"
}

/** The columns a job keeps when it moves to history. */
internal const val KEPT = "id, tenant_id, kind, dedupe_key, payload, payload_version, carried, run_at, expires_at, " +
    "priority, attempts, max_attempts, locked_by, last_error, created_at"

/** Binds [values] in order from the first parameter. A null is sent as text, so the SQL casts what may be null. */
internal fun PreparedStatement.bind(values: List<Any?>) {
    values.forEachIndexed { at, value ->
        when (value) {
            null -> setNull(at + 1, Types.VARCHAR)
            is Instant -> setObject(at + 1, value.atOffset(ZoneOffset.UTC))
            else -> setObject(at + 1, value)
        }
    }
}
