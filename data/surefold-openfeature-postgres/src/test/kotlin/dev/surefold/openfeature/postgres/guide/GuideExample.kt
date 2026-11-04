package dev.surefold.openfeature.postgres.guide

// The example docs/flags-per-tenant.md shows, from the imports down: GuideSuite holds the page to this file.
import dev.openfeature.sdk.OpenFeatureAPI
import dev.openfeature.sdk.providers.memory.InMemoryProvider
import dev.surefold.openfeature.Flag
import dev.surefold.openfeature.postgres.FLAGS_CHANNEL
import dev.surefold.openfeature.postgres.PostgresFlags
import dev.surefold.postgres.notify.PostgresNotifications
import dev.surefold.stream.Run
import dev.surefold.tenancy.TenantId
import dev.surefold.tenancy.withTenant
import javax.sql.DataSource
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

val CheckoutPaused = Flag.boolean("checkout.paused", default = false)

// [app] is the application's pool; [listener] a source of its own, since listening holds one connection.
fun flags(app: DataSource, listener: DataSource): Pair<PostgresFlags, Run<Nothing, Long>> {
    val provider = PostgresFlags(app, fallback = InMemoryProvider(emptyMap()), ttl = 30.seconds, stale = 10.minutes)
    OpenFeatureAPI.getInstance().setProviderAndWait(provider)
    // Start it beside the provider: another process's change, or one typed in psql, is seen within moments.
    val listening = provider.listening(PostgresNotifications.listen(listener, setOf(FLAGS_CHANNEL)))
    return provider to listening
}

// The tenant's row, else the row every tenant gets, else the fallback, else false.
fun paused(tenant: TenantId): Boolean = withTenant(tenant) { CheckoutPaused.value() }

fun pause(provider: PostgresFlags, admin: DataSource, tenant: TenantId, operator: String) =
    admin.connection.use { connection ->
        provider.set(connection, "checkout.paused", tenant, "true", reason = "fire alarm", by = operator)
    }
