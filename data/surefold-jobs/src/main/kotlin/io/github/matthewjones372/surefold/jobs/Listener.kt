package io.github.matthewjones372.surefold.jobs

import io.github.matthewjones372.surefold.clock
import io.github.matthewjones372.surefold.logInfo
import io.github.matthewjones372.surefold.logWarn
import io.github.matthewjones372.surefold.surefoldFuture
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.sql.Connection
import java.sql.SQLException
import java.util.concurrent.CompletableFuture
import javax.sql.DataSource
import kotlin.time.Duration

/**
 * One connection that `LISTEN`s on [CHANNEL] and calls [wake] for every batch of notifications, so a job enqueued to
 * run now starts without waiting for the next poll. Notifications are read through the Postgres driver's
 * `PGConnection.getNotifications(int)`, found by `unwrap` and reflection so this module names no driver. On another
 * driver it logs once and ends, and the worker only polls.
 */
internal class Listener(
    private val source: DataSource,
    private val retry: Duration,
    private val wake: () -> Unit,
) : AutoCloseable {

    @Volatile
    private var stopping = false
    private val running: CompletableFuture<Unit> = surefoldFuture { listening() }

    private fun listening() {
        while (!stopping) {
            val listened = try {
                source.connection.use(::listenOn)
            } catch (failed: SQLException) {
                rest(failed)
            } catch (failed: InvocationTargetException) {
                rest(failed.cause ?: failed)
            }
            if (!listened) return
        }
    }

    /** Listens on [connection] until closing, and answers false when the driver cannot listen at all. */
    private fun listenOn(connection: Connection): Boolean {
        val notifications = notificationsOf(connection) ?: return false
        connection.createStatement().use { it.execute("listen $CHANNEL") }
        logInfo("surefold-jobs: listening on $CHANNEL")
        while (!stopping) {
            val received = notifications(connection, WAIT_MILLIS) as Array<*>?
            if (!received.isNullOrEmpty()) wake()
        }
        return true
    }

    private fun rest(failed: Throwable): Boolean {
        if (stopping) return true
        logWarn("surefold-jobs: lost the connection listening on $CHANNEL, and will listen again: $failed")
        try {
            clock.get().sleep(retry)
        } catch (_: InterruptedException) {
            stopping = true
        }
        return true
    }

    /** The bound `getNotifications(int)` of the driver's connection, or null when the driver is not Postgres's. */
    private fun notificationsOf(connection: Connection): ((Connection, Int) -> Any?)? {
        val pg = try {
            Class.forName(PG_CONNECTION, false, connection.javaClass.classLoader)
        } catch (_: ClassNotFoundException) {
            null
        }
        if (pg == null || !connection.isWrapperFor(pg)) {
            logInfo("surefold-jobs: the driver is not Postgres's, so workers poll and do not listen")
            return null
        }
        val method: Method = pg.getMethod("getNotifications", Int::class.javaPrimitiveType)
        return { held, millis -> method.invoke(held.unwrap(pg), millis) }
    }

    override fun close() {
        stopping = true
        running.join()
    }

    private companion object {
        const val PG_CONNECTION = "org.postgresql.PGConnection"

        // How long one read waits for a notification, and so how long close waits for the listener.
        const val WAIT_MILLIS = 250
    }
}
