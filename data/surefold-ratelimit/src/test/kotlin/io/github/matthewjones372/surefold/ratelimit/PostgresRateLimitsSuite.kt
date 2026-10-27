package io.github.matthewjones372.surefold.ratelimit

import io.github.matthewjones372.surefold.actor.journal.jdbc.Postgres
import liquibase.Liquibase
import liquibase.database.DatabaseFactory
import liquibase.database.jvm.JdbcConnection
import liquibase.resource.ClassLoaderResourceAccessor

/** The Postgres buckets keep the contract the in-memory ones keep. */
object PostgresRateLimitsSuite : RateLimitsContract() {
    override fun limits(): RateLimits = PostgresRateLimits(
        Postgres.empty().also { source ->
            source.connection.use { connection ->
                val database =
                    DatabaseFactory.getInstance().findCorrectDatabaseImplementation(JdbcConnection(connection))
                Liquibase("surefold/ratelimit/postgres.sql", ClassLoaderResourceAccessor(), database)
                    .use { it.update() }
            }
        },
    )
}
