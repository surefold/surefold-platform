package io.github.matthewjones372.surefold.app.flyway

import io.github.matthewjones372.surefold.app.Migrated
import io.github.matthewjones372.surefold.app.Module
import io.github.matthewjones372.surefold.app.Wiring
import io.github.matthewjones372.surefold.app.single
import io.github.matthewjones372.surefold.logInfo
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.FlywayException
import org.flywaydb.database.postgresql.PostgreSQLConfigurationExtension
import javax.sql.DataSource

/**
 * What a service may change about a migration; the rest is pinned. [mixed] is off because Postgres DDL is
 * transactional: a script that needs `create index concurrently` says so in its own `.sql.conf`. [transactionalLock]
 * is off so that such a script does not wait on the lock's own transaction, and [connectRetries] is 0 so that a
 * service that cannot reach Postgres fails to start and is restarted.
 */
data class FlywaySettings(
    val mixed: Boolean = false,
    val transactionalLock: Boolean = false,
    val connectRetries: Int = 0,
)

/**
 * Runs the scripts at [service], a Flyway location such as `classpath:db/migration`, against the graph's
 * [DataSource], once, before anything that takes a [Migrated] is built. Flyway takes its connections from the source
 * and gives them back before the node answers. A script that fails, or an applied one since edited, refuses the start.
 */
fun flyway(service: String, settings: FlywaySettings = FlywaySettings()): Module =
    single { source: DataSource -> migrated { migrate(source, service, settings) } }

/**
 * [flyway] on a source [connect] opens from [S], a node in the graph, such as a pool logged in as the schema's owner.
 * It is closed, if it is `AutoCloseable`, before the node answers: the application never holds it.
 */
inline fun <reified S : Any> flyway(
    service: String,
    settings: FlywaySettings = FlywaySettings(),
    noinline connect: (S) -> DataSource,
): Module = single { key: S -> migrated { connected(connect(key)) { source -> migrate(source, service, settings) } } }

/**
 * Runs the scripts at [service] against [source] now, and answers how many it applied. For a database outside a
 * graph; [flyway] is this as a node.
 */
fun migrate(source: DataSource, service: String, settings: FlywaySettings = FlywaySettings()): Int {
    val applied = configured(source, service, settings).migrate().migrationsExecuted
    logInfo("flyway: $service, $applied migration(s) applied")
    return applied
}

/** Flyway as every migration here runs it: what [FlywaySettings] does not offer is pinned. */
internal fun configured(source: DataSource, location: String, settings: FlywaySettings): Flyway =
    Flyway.configure()
        // Set on the extension itself: as a property, Flyway wants jackson-databind on the classpath to read it.
        .apply {
            getConfigurationExtension(PostgreSQLConfigurationExtension::class.java).isTransactionalLock =
                settings.transactionalLock
        }
        .dataSource(source)
        .locations(location)
        // A clean drops the schema, which no service wants in production.
        .cleanDisabled(true)
        // An applied script edited since fails the start with its checksum.
        .validateOnMigrate(true)
        // It would baseline any non-empty schema, silently.
        .baselineOnMigrate(false)
        .outOfOrder(false)
        // A `${` in a function body is SQL.
        .placeholderReplacement(false)
        .mixed(settings.mixed)
        .connectRetries(settings.connectRetries)
        .load()

/** [migrate]'s count as a [Migrated], or the start refused with what Flyway said. */
@PublishedApi
internal fun Wiring.migrated(migrate: () -> Int): Migrated =
    try {
        Migrated(migrate())
    } catch (failed: FlywayException) {
        refuse("the migration failed: ${failed.message}")
    }

/** [use] on [source], closing it afterwards if it can be closed. */
@PublishedApi
internal fun <T> connected(source: DataSource, use: (DataSource) -> T): T =
    try {
        use(source)
    } finally {
        (source as? AutoCloseable)?.close()
    }
