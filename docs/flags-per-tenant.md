# Flags per tenant from Postgres

A flag declared with `surefold-openfeature`, as surefold-integrations'
[feature flags guide](https://github.com/surefold/surefold-integrations/blob/main/docs/flags.md) shows, read per tenant
from a Postgres table.

`surefold-openfeature-postgres`'s `PostgresFlags` reads flags from a table, one
row per flag for every tenant and one per tenant that differs, so an operator
can pause checkout for one tenant tonight without a deploy. The read runs in
the tenant's own transaction, under the table's row-level security policy, so
a tenant never sees another tenant's rows.

```kotlin
dependencies {
    // brings surefold-openfeature, surefold-tenancy, surefold-cache and surefold-postgres-notify
    implementation("dev.surefold:surefold-openfeature-postgres:0.18.0")
}
```

The table ships as a changelog: `<include file="surefold/openfeature-postgres/postgres.sql"/>`
on Liquibase, or `flyway(SurefoldSchema.OPENFEATURE_POSTGRES, …)` on Flyway. It
holds `surefold_feature_flags (key, tenant_id, value, reason, updated_by, updated_at)`
with a null `tenant_id` for the row every tenant gets, forced row-level
security with this policy, and a trigger that notifies each change:

```sql
create policy surefold_feature_flags_read on surefold_feature_flags for select
    using (tenant_id is null or tenant_id = current_setting('app.tenant_id', true));
```

Grants are the service's: `select` to the application's role, and writes to
the role its admin endpoint uses, which needs `BYPASSRLS` to write any tenant's
row, since the table has no write policy.

```sql
grant select on surefold_feature_flags to app;
grant select, insert, update, delete on surefold_feature_flags to app_admin;
```

<!-- flags-postgres -->
```kotlin
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
```

- **Whose rows**: the evaluation context's `tenant` attribute if set, so a
  tool can ask as any tenant, else the one `withTenant` bound. With neither,
  only the rows every tenant gets answer.
- **Types**: a row's text is read as the type asked for. One that does not
  parse answers the default with `PARSE_ERROR`, counted like any other
  fall-back. There are no object flags.
- **Cache**: a tenant's rows are read in one query and kept for `ttl`, however
  many evaluate at once. `set` and `clear` drop them at once in this process,
  and `listening` in every other, a row every tenant gets dropping them all.
- **Postgres down**: the last rows read answer for up to `stale`, and the
  provider reports `STALE`, so the graph reports not ready. Past that, or
  with nothing read yet, flags answer the fallback, and it reports `ERROR`.
