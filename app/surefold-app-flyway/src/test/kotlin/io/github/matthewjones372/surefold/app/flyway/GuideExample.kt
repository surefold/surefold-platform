package io.github.matthewjones372.surefold.app.flyway

// The example docs/flyway.md shows, from the imports down: FlywayGuideSuite holds the page to this file.
import io.github.matthewjones372.surefold.app.Migrated
import io.github.matthewjones372.surefold.app.Module
import io.github.matthewjones372.surefold.app.single
import javax.sql.DataSource

class OrderRepo(val source: DataSource)

/** The jobs tables, then the service's own scripts, before anything reads the database. */
val database: Module =
    flyway(SurefoldSchema.JOBS, service = "classpath:db/handover", fromLiquibase = "shop:0014-orders") +
        single { db: DataSource, _: Migrated -> OrderRepo(db) }
