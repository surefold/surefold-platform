package io.github.matthewjones372.surefold.postgres.notify

import io.github.matthewjones372.surefold.logInfo
import io.github.matthewjones372.surefold.stream.Stream
import io.github.matthewjones372.surefold.stream.blocking
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.sql.Connection
import javax.sql.DataSource

/** Postgres's `LISTEN` as a [Stream], and `pg_notify` to send to it. */
object PostgresNotifications {

    /**
     * Every notification sent on [channels] after this run's connection is listening, in commit order. Each run takes
     * one connection from [source], held until the run stops, so [source] should be one of its own, outside the pool;
     * run it once per process and share it through a `Hub`. A connection that fails ends the run.
     */
    fun listen(source: DataSource, channels: Set<String>): Stream<Nothing, Notice> {
        require(channels.isNotEmpty()) { "PostgresNotifications.listen names no channel to listen on" }
        channels.forEach(::checked)
        return Stream.blocking(
            open = { Listening.open(source, channels) },
            next = Listening::next,
            wake = Listening::wake,
            close = Listening::close,
        )
    }

    /** Sends [payload] on [channel] in [connection]'s transaction, so listeners hear it once that commits. */
    fun notify(connection: Connection, channel: String, payload: String) {
        checked(channel)
        val bytes = payload.toByteArray(Charsets.UTF_8).size
        require(bytes <= MAX_PAYLOAD_BYTES) {
            "a notification on $channel carries at most $MAX_PAYLOAD_BYTES bytes, and this one is $bytes: send an id"
        }
        connection.prepareStatement("select pg_notify(?, ?)").use { statement ->
            statement.setString(1, channel)
            statement.setString(2, payload)
            statement.executeQuery().close()
        }
    }

    // LISTEN takes no bound parameter, and folds an unquoted name to lower case where pg_notify does not.
    private fun checked(channel: String) {
        require(CHANNEL.matches(channel)) {
            "a channel is a lower-case identifier of at most 63 characters, such as bookings_live, " +
                "and '$channel' is not"
        }
    }

    private val CHANNEL = Regex("[a-z_][a-z0-9_]{0,62}")

    // Postgres's limit is 8,000 bytes, its terminating zero included.
    private const val MAX_PAYLOAD_BYTES = 7_999
}

/** One run's connection, listening on its channels, and what its last read heard that the stream has not taken. */
private class Listening(private val connection: Connection, private val driver: Driver) {

    private val heard = ArrayDeque<Notice>()

    @Volatile
    private var woken = false

    /** The next notification, or null once woken to stop. */
    fun next(): Notice? {
        while (heard.isEmpty()) {
            if (woken) return null
            heard.addAll(driver.notifications(connection, WAIT_MILLIS))
        }
        return heard.removeFirst()
    }

    /** Ends a read within [WAIT_MILLIS], so the connection is never closed under one. */
    fun wake() {
        woken = true
    }

    fun close() {
        connection.close()
    }

    companion object {
        // How long one read waits for a notification, and so how long a stop waits for the read.
        const val WAIT_MILLIS = 250

        // Whatever opening throws, the connection it took goes back before the stream fails with it.
        @Suppress("TooGenericExceptionCaught")
        fun open(source: DataSource, channels: Set<String>): Listening {
            val connection = source.connection
            try {
                val driver = Driver.of(connection)
                connection.autoCommit = true
                connection.createStatement().use { statement -> channels.forEach { statement.execute("listen $it") } }
                logInfo("surefold-postgres-notify: listening on ${channels.joinToString()}")
                return Listening(connection, driver)
            } catch (failed: Throwable) {
                connection.close()
                throw failed
            }
        }
    }
}

/** The Postgres driver's notifications, found by `unwrap` and reflection so this module names no driver. */
private class Driver(private val pg: Class<*>, notification: Class<*>) {

    private val read: Method = pg.getMethod("getNotifications", Int::class.javaPrimitiveType)
    private val name: Method = notification.getMethod("getName")
    private val parameter: Method = notification.getMethod("getParameter")
    private val pid: Method = notification.getMethod("getPID")

    /** What [connection] heard within [millis], none if nothing. */
    fun notifications(connection: Connection, millis: Int): List<Notice> = unreflected {
        val received = read.invoke(connection.unwrap(pg), millis) as Array<*>?
        received.orEmpty().map { notification ->
            Notice.Received(
                channel = name.invoke(notification) as String,
                payload = parameter.invoke(notification) as String,
                pid = pid.invoke(notification) as Int,
            )
        }
    }

    companion object {
        /** The driver behind [connection], or a refusal naming the connection that is not Postgres's. */
        fun of(connection: Connection): Driver {
            val pg = loaded(connection, "org.postgresql.PGConnection")
            val notification = loaded(connection, "org.postgresql.PGNotification")
            check(pg != null && notification != null && connection.isWrapperFor(pg)) {
                "PostgresNotifications.listen needs the Postgres JDBC driver, and got a ${connection.javaClass.name}"
            }
            return Driver(pg, notification)
        }

        private fun loaded(connection: Connection, name: String): Class<*>? = try {
            Class.forName(name, false, connection.javaClass.classLoader)
        } catch (_: ClassNotFoundException) {
            null
        }

        /** What [call] answers, throwing the driver's own exception rather than reflection's wrapper around it. */
        private fun <A> unreflected(call: () -> A): A = try {
            call()
        } catch (thrown: InvocationTargetException) {
            throw thrown.cause ?: thrown
        }
    }
}
