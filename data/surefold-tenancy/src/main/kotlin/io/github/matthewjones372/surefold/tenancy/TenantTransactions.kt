package io.github.matthewjones372.surefold.tenancy

import io.github.matthewjones372.surefold.clock
import io.github.matthewjones372.surefold.fail.Either
import io.github.matthewjones372.surefold.fail.left
import io.github.matthewjones372.surefold.fail.right
import io.github.matthewjones372.surefold.logInfo
import java.sql.Connection
import java.time.ZoneOffset
import javax.sql.DataSource

/** The setting a row-level security policy reads the tenant from: `current_setting('app.tenant_id', true)`. */
const val TENANT_SETTING = "app.tenant_id"

/** Why a tenant's transaction was not run. */
sealed interface TenancyError {
    /** No tenant was bound with `withTenant`, so there is no one whose rows the transaction could see. */
    data object NoTenant : TenancyError
}

/**
 * Transactions that Postgres scopes to one tenant. [app] is the application's own `DataSource`, whose role is subject
 * to row-level security: neither a superuser nor the owner of a table whose security is not forced. [system], if
 * given, reaches the database as a role with `BYPASSRLS`, for the work that must cross tenants, and nothing else.
 */
class TenantTransactions(private val app: DataSource, private val system: DataSource? = null) {

    /**
     * Runs [block] in a transaction on [app] as the bound tenant's: `app.tenant_id` is set for that transaction only,
     * as `SET LOCAL` sets it, so a pooled connection carries no tenant to its next user. Commits when [block]
     * returns, and rolls back when it throws. With no tenant bound, answers [TenancyError.NoTenant] and opens nothing.
     */
    fun <A> transaction(block: (Connection) -> A): Either<TenancyError, A> {
        val id = currentTenant() ?: return TenancyError.NoTenant.left()
        return app.connection.use { connection ->
            inTransaction(connection) {
                connection.prepareStatement("select set_config('$TENANT_SETTING', ?, true)").use { statement ->
                    statement.setString(1, id.value)
                    statement.execute()
                }
                block(connection)
            }
        }.right()
    }

    /** [transaction] as [id]'s, binding it for [block] and every fork [block] opens. */
    fun <A> transaction(id: TenantId, block: (Connection) -> A): A =
        withTenant(id) { (transaction(block) as Either.Right).value }

    /**
     * Runs [block] across every tenant, on [system], and records who ran it and why in `surefold_tenancy_audit`, in
     * the same transaction, and as a log line. For the jobs worker, migrations of data, and an operator's support
     * work; never for a request.
     */
    fun <A> system(actor: String, reason: String, block: (Connection) -> A): A {
        val source = checkNotNull(system) { "TenantTransactions was made without a system DataSource." }
        require(actor.isNotBlank() && reason.isNotBlank()) { "A system transaction names who runs it, and why." }
        logInfo("surefold-tenancy: system transaction by $actor: $reason")
        return source.connection.use { connection ->
            inTransaction(connection) {
                connection.prepareStatement(AUDIT).use { statement ->
                    listOf(actor, reason, clock.get().now().atOffset(ZoneOffset.UTC))
                        .forEachIndexed { at, value -> statement.setObject(at + 1, value) }
                    statement.executeUpdate()
                }
                block(connection)
            }
        }
    }

    private fun <A> inTransaction(connection: Connection, block: () -> A): A {
        val wasAuto = connection.autoCommit
        connection.autoCommit = false
        var committed = false
        try {
            return block().also {
                connection.commit()
                committed = true
            }
        } finally {
            if (!committed) connection.rollback()
            connection.autoCommit = wasAuto
        }
    }

    private companion object {
        const val AUDIT = "insert into surefold_tenancy_audit (actor, reason, at) values (?, ?, ?)"
    }
}
