package io.github.matthewjones372.lark.bank

import io.github.matthewjones372.lark.actor.journal.jdbc.JdbcJournal
import org.h2.jdbcx.JdbcDataSource
import org.postgresql.ds.PGSimpleDataSource
import javax.sql.DataSource

/** An H2 database in this JVM named [name], with the journal's tables, for as long as the JVM runs. */
internal fun h2(name: String): DataSource = database("jdbc:h2:mem:$name;DB_CLOSE_DELAY=-1")

/**
 * The database at [url], Postgres or H2, with the journal's tables created if it has none. The library leaves its
 * tables to a service's migrations; this application has none, so it applies the DDL the jar ships, once.
 */
internal fun database(url: String): DataSource {
    val postgres = url.startsWith("jdbc:postgresql:")
    val source = if (postgres) PGSimpleDataSource().apply { setURL(url) } else JdbcDataSource().apply { setURL(url) }
    source.connection.use { connection ->
        val tables = connection.metaData.getTables(null, null, "%", arrayOf("TABLE")).use { found ->
            generateSequence { if (found.next()) found.getString("TABLE_NAME").lowercase() else null }.toSet()
        }
        if ("lark_journal" !in tables) {
            val ddl = checkNotNull(JdbcJournal::class.java.getResource(if (postgres) POSTGRES else H2)).readText()
            connection.createStatement().use { it.execute(ddl) }
        }
    }
    return source
}

private const val H2 = "/lark/journal/jdbc/h2.sql"
private const val POSTGRES = "/lark/journal/jdbc/postgres.sql"
