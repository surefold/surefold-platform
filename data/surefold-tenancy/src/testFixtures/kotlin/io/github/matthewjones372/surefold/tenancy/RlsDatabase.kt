package io.github.matthewjones372.surefold.tenancy

import io.github.matthewjones372.surefold.actor.journal.jdbc.Postgres
import liquibase.Liquibase
import liquibase.database.DatabaseFactory
import liquibase.database.jvm.JdbcConnection
import liquibase.resource.ClassLoaderResourceAccessor
import org.postgresql.ds.PGSimpleDataSource
import java.lang.reflect.Proxy
import java.sql.Connection
import javax.sql.DataSource

/**
 * A fresh database with the tenancy changelog applied, and three ways in: [owner], a superuser that row-level
 * security never binds; [app], a role it binds; and [system], a role with BYPASSRLS. With [bookings], it has a
 * `bookings` table under a forced tenant policy, the one docs/tenancy.md gives to copy.
 */
class RlsDatabase(bookings: Boolean = true) {
    private val address = Postgres.emptyUrl()

    val owner: DataSource = source(user = null)
    val app: DataSource = source("surefold_app")
    val system: DataSource = source("surefold_system")

    init {
        check(rolesMade)
        apply("surefold/tenancy/postgres.sql")
        execute(GRANTS)
        if (bookings) {
            execute(TABLE)
            grantTo("bookings")
        }
    }

    /** Applies [changelog], a classpath resource, as the owner. */
    fun apply(changelog: String) {
        // Liquibase closes the connection it is handed.
        owner.connection.use { connection ->
            val database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(JdbcConnection(connection))
            Liquibase(changelog, ClassLoaderResourceAccessor(), database).use { it.update() }
        }
    }

    /** Lets the application and system roles read and write [table]. */
    fun grantTo(table: String) =
        execute("grant select, insert, update, delete on $table to surefold_app, surefold_system")

    /** Runs [sql] as the owner. */
    fun execute(sql: String) {
        owner.connection.use { connection -> connection.createStatement().use { it.execute(sql) } }
    }

    private fun source(user: String?): DataSource = PGSimpleDataSource().also { source ->
        source.setUrl(address)
        if (user != null) {
            source.user = user
            source.password = user
        }
    }

    private companion object {
        // Roles are the server's, not the database's: made once per test JVM, and kept if an earlier run made them.
        val rolesMade: Boolean by lazy {
            PGSimpleDataSource().apply { setUrl(Postgres.emptyUrl()) }.connection.use { connection ->
                connection.createStatement().use { it.execute(ROLES) }
            }
            true
        }

        const val ROLES = """
            do $$ begin
                create role surefold_app login password 'surefold_app';
            exception when duplicate_object or unique_violation then null; end $$;
            do $$ begin
                create role surefold_system login password 'surefold_system' bypassrls;
            exception when duplicate_object or unique_violation then null; end $$;
        """

        const val TABLE = """
            create table bookings (
                id        bigint generated always as identity primary key,
                tenant_id text   not null default current_setting('app.tenant_id', true),
                name      text   not null
            );
            alter table bookings enable row level security;
            alter table bookings force row level security;
            create policy tenant_isolation on bookings
                using (tenant_id = current_setting('app.tenant_id', true))
                with check (tenant_id = current_setting('app.tenant_id', true));
        """

        const val GRANTS = """
            grant insert on surefold_tenancy_audit to surefold_system;
            grant select on surefold_tenancy_audit to surefold_app, surefold_system;
        """
    }
}

/** A DataSource that hands out [held] every time and never closes it: one pooled connection, used twice. */
@Suppress("FunctionName")
fun SingleConnection(held: Connection): DataSource {
    val loader = DataSource::class.java.classLoader
    val kept = Proxy.newProxyInstance(loader, arrayOf(Connection::class.java)) { _, method, args ->
        if (method.name == "close") null else method.invoke(held, *(args ?: emptyArray()))
    } as Connection
    return Proxy.newProxyInstance(loader, arrayOf(DataSource::class.java)) { _, method, _ ->
        if (method.name == "getConnection") kept else throw UnsupportedOperationException(method.name)
    } as DataSource
}
