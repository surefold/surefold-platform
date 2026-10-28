package io.github.matthewjones372.surefold.openfeature.postgres

import dev.openfeature.sdk.ErrorCode
import dev.openfeature.sdk.EvaluationContext
import dev.openfeature.sdk.FeatureProvider
import dev.openfeature.sdk.Metadata
import dev.openfeature.sdk.ProviderEvaluation
import dev.openfeature.sdk.Reason
import dev.openfeature.sdk.Value
import io.github.matthewjones372.surefold.fail.Either
import io.github.matthewjones372.surefold.tenancy.TenantId
import io.github.matthewjones372.surefold.tenancy.TenantTransactions
import io.github.matthewjones372.surefold.tenancy.currentTenant
import io.github.matthewjones372.surefold.tenancy.withTenant
import java.sql.Connection
import javax.sql.DataSource

/** The evaluation context attribute a flag is read for before the bound tenant, so a tool can ask as any tenant. */
const val TENANT_ATTRIBUTE = "tenant"

/**
 * Flags per tenant from `surefold_feature_flags` (spec 0240), read on [app], the application's own `DataSource`,
 * under the table's row-level security policy. A flag answers, in order:
 * - the tenant's row, with the reason `TARGETING_MATCH`;
 * - the row every tenant gets, whose `tenant_id` is null, with the reason `STATIC`;
 * - [fallback]'s answer, as it gives it, a `FlagNotFoundError` for a flag it does not hold included;
 * - the caller's default.
 *
 * The tenant is the context's [TENANT_ATTRIBUTE] if set, else the bound one; with neither, only null rows answer. A
 * row's text is read as the type asked for, and one that does not parse answers the default with `PARSE_ERROR`.
 * There are no object flags: an object is [fallback]'s, or the default. [fallback] starts and stops with this provider.
 */
class PostgresFlags(
    private val app: DataSource,
    private val fallback: FeatureProvider? = null,
) : FeatureProvider {
    private val tenants = TenantTransactions(app)

    override fun getMetadata() = Metadata { "surefold-postgres" }

    // The SDK starts and stops only the provider it was given, so the fallback is started and stopped with this one.
    override fun initialize(evaluationContext: EvaluationContext) {
        fallback?.initialize(evaluationContext)
    }

    override fun shutdown() {
        fallback?.shutdown()
    }

    override fun getBooleanEvaluation(key: String, defaultValue: Boolean, ctx: EvaluationContext) =
        evaluate(key, defaultValue, ctx, "a boolean", { it.toBooleanStrictOrNull() }) {
            fallback?.getBooleanEvaluation(key, defaultValue, ctx)
        }

    override fun getStringEvaluation(key: String, defaultValue: String, ctx: EvaluationContext) =
        evaluate(key, defaultValue, ctx, "a string", { it }) { fallback?.getStringEvaluation(key, defaultValue, ctx) }

    override fun getIntegerEvaluation(key: String, defaultValue: Int, ctx: EvaluationContext) =
        evaluate(key, defaultValue, ctx, "an integer", { it.toIntOrNull() }) {
            fallback?.getIntegerEvaluation(key, defaultValue, ctx)
        }

    override fun getDoubleEvaluation(key: String, defaultValue: Double, ctx: EvaluationContext) =
        evaluate(key, defaultValue, ctx, "a double", { it.toDoubleOrNull() }) {
            fallback?.getDoubleEvaluation(key, defaultValue, ctx)
        }

    override fun getObjectEvaluation(key: String, defaultValue: Value, ctx: EvaluationContext) =
        fallback?.getObjectEvaluation(key, defaultValue, ctx) ?: answer(defaultValue, Reason.DEFAULT)

    private fun <A> evaluate(
        key: String,
        default: A,
        ctx: EvaluationContext,
        type: String,
        parse: (String) -> A?,
        fallen: () -> ProviderEvaluation<A>?,
    ): ProviderEvaluation<A> {
        val row = rows(scopeOf(ctx))[key] ?: return fallen() ?: answer(default, Reason.DEFAULT)
        val parsed = parse(row.value) ?: return ProviderEvaluation.builder<A>()
            .value(default)
            .reason(Reason.ERROR.name)
            .errorCode(ErrorCode.PARSE_ERROR)
            .errorMessage("$key is '${row.value}', which is not $type")
            .build()
        return answer(parsed, if (row.tenants) Reason.TARGETING_MATCH else Reason.STATIC)
    }

    private fun scopeOf(ctx: EvaluationContext): TenantId? =
        ctx.getValue(TENANT_ATTRIBUTE)?.asString()?.takeIf { it.isNotBlank() }?.let(::TenantId) ?: currentTenant()

    // The tenant's rows and the null rows in one query, each key's own row before the null one.
    private fun rows(tenant: TenantId?): Map<String, Row> {
        val read = if (tenant == null) {
            everyone()
        } else {
            when (val answered = withTenant(tenant) { tenants.transaction(::select) }) {
                is Either.Right -> answered.value
                is Either.Left -> error("a tenant was bound, and no tenant transaction ran: ${answered.value}")
            }
        }
        return read.sortedBy { it.tenants }.associateBy { it.key }
    }

    // No tenant: a transaction with app.tenant_id cleared, so a pooled connection's session value is not read as one.
    private fun everyone(): List<Row> = app.connection.use { connection ->
        val auto = connection.autoCommit
        connection.autoCommit = false
        try {
            connection.prepareStatement("select set_config('app.tenant_id', '', true)").use { it.execute() }
            select(connection).also { connection.commit() }
        } catch (failed: java.sql.SQLException) {
            connection.rollback()
            throw failed
        } finally {
            connection.autoCommit = auto
        }
    }

    private fun select(connection: Connection): List<Row> = connection.prepareStatement(SELECT).use { statement ->
        statement.executeQuery().use { rows ->
            generateSequence {
                if (!rows.next()) return@generateSequence null
                Row(rows.getString("key"), rows.getString("value"), rows.getBoolean("tenants"))
            }.toList()
        }
    }

    private class Row(val key: String, val value: String, val tenants: Boolean)

    private companion object {
        // The policy decides what a tenant sees; the condition keeps the same answer on a role it does not bind.
        const val SELECT = """
            select key, value, tenant_id is not null as tenants from surefold_feature_flags
            where tenant_id is null or tenant_id = current_setting('app.tenant_id', true)
        """

        fun <A> answer(value: A, reason: Reason): ProviderEvaluation<A> =
            ProviderEvaluation.builder<A>().value(value).reason(reason.name).build()
    }
}
