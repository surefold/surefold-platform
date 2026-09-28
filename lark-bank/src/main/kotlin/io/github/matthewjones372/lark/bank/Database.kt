package io.github.matthewjones372.lark.bank

import io.github.matthewjones372.lark.app.liquibase.migrate
import org.postgresql.ds.PGSimpleDataSource
import javax.sql.DataSource

/**
 * The Postgres database at [url], with the journal's tables. The library leaves its tables to a service's migrations;
 * this application has no others, so it runs the changelog the jar ships, which Liquibase applies once.
 */
internal fun database(url: String): DataSource =
    PGSimpleDataSource().apply { setURL(url) }.also { migrate(it, "lark/journal/jdbc/postgres.sql") }
