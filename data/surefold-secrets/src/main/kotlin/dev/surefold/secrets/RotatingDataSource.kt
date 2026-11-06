package dev.surefold.secrets

import dev.surefold.Shared
import dev.surefold.SingleFlight
import dev.surefold.fail.Either
import java.io.PrintWriter
import java.sql.Connection
import java.sql.SQLException
import java.util.logging.Logger
import javax.sql.DataSource

/**
 * A [DataSource] that refreshes its database password from [secrets] when Postgres refuses a connection with an
 * authentication failure (`28P01` or `28000`). Concurrent callers coalesce their refresh through [SingleFlight], and
 * the underlying [DataSource] is swapped atomically in [Shared] before retrying once.
 */
class RotatingDataSource(
    private val secrets: SecretStore,
    private val secretId: String,
    initialPassword: String,
    private val extractPassword: (String) -> Either<SecretError, String> = { Either.Right(it) },
    private val buildDataSource: (String) -> DataSource,
) : DataSource, AutoCloseable {

    private data class PoolState(val password: String, val pool: DataSource)

    private val state = Shared(PoolState(initialPassword, buildDataSource(initialPassword)), "rotating-datasource")
    private val flight = SingleFlight<String, SecretError, PoolState>("rotating-datasource")

    override fun getConnection(): Connection {
        val snapshot = state.peek()
        return try {
            snapshot.pool.connection
        } catch (failed: SQLException) {
            if (!isAuthenticationFailure(failed)) throw failed
            when (val refreshed = refreshIfStale(snapshot.password)) {
                is Either.Left -> throw failed
                is Either.Right -> refreshed.value.pool.connection
            }
        }
    }

    override fun getConnection(username: String?, password: String?): Connection = getConnection()

    private fun refreshIfStale(failedPassword: String): Either<SecretError, PoolState> =
        flight.run(secretId) {
            val current = state.peek()
            if (current.password != failedPassword) {
                Either.Right(current)
            } else {
                when (val fetched = readNextPassword(failedPassword)) {
                    is Either.Left -> fetched

                    is Either.Right -> {
                        val nextPool = buildDataSource(fetched.value)
                        val nextState = PoolState(fetched.value, nextPool)
                        state.update { nextState }
                        closeQuietly(current.pool)
                        Either.Right(nextState)
                    }
                }
            }
        }

    private fun readNextPassword(failedPassword: String): Either<SecretError, String> {
        val current = secrets.getSecret(secretId)
        if (current is Either.Right) {
            val extracted = extractPassword(current.value.value)
            if (extracted is Either.Right && extracted.value != failedPassword) {
                return extracted
            }
        }
        val pending = secrets.getSecret(secretId, InMemorySecrets.AWS_PENDING)
        if (pending is Either.Right) {
            val extracted = extractPassword(pending.value.value)
            if (extracted is Either.Right) return extracted
        }
        return when (current) {
            is Either.Left -> current
            is Either.Right -> extractPassword(current.value.value)
        }
    }

    override fun close() {
        flight.closeAndJoin()
        closeQuietly(state.peek().pool)
    }

    override fun getLogWriter(): PrintWriter? = state.peek().pool.logWriter

    override fun setLogWriter(out: PrintWriter?) {
        state.peek().pool.logWriter = out
    }

    override fun setLoginTimeout(seconds: Int) {
        state.peek().pool.loginTimeout = seconds
    }

    override fun getLoginTimeout(): Int = state.peek().pool.loginTimeout

    override fun getParentLogger(): Logger = state.peek().pool.parentLogger

    override fun <T : Any?> unwrap(iface: Class<T>): T = state.peek().pool.unwrap(iface)

    override fun isWrapperFor(iface: Class<*>): Boolean = state.peek().pool.isWrapperFor(iface)

    private fun closeQuietly(pool: DataSource) {
        if (pool is AutoCloseable) {
            Either.catch { pool.close() }
        }
    }

    companion object {
        private val AUTH_SQL_STATES = setOf("28P01", "28000")

        internal fun isAuthenticationFailure(error: Throwable): Boolean =
            generateSequence(error) { it.cause }
                .filterIsInstance<SQLException>()
                .any { it.sqlState in AUTH_SQL_STATES }
    }
}
