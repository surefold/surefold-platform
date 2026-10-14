package io.github.matthewjones372.lark.bank

import com.sun.net.httpserver.HttpHandler
import io.github.matthewjones372.lark.logDebug
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

/** One server-sent event: the `event:` line's [type], an `id:` line if it has an [id], and its JSON [data]. */
internal data class Event(val type: String, val data: String, val id: Long? = null) {
    fun frame(): String = buildString {
        append("event: ").append(type).append('\n')
        id?.let { append("id: ").append(it).append('\n') }
        data.lines().forEach { append("data: ").append(it).append('\n') }
        append('\n')
    }
}

/** A comment line: a proxy that closes an idle stream sees traffic, and `EventSource` ignores it. */
internal const val HEARTBEAT = ": heartbeat\n\n"

/** The event type the newest of supersedes the older, so it is the first dropped when a browser falls behind. */
internal const val STATS = "stats"

/** How a stream behaves: a [heartbeat] when idle, a queue of [capacity] events, and closed once full for [fullFor]. */
internal data class Streaming(
    val heartbeat: Duration = 15.seconds,
    val capacity: Int = 256,
    val fullFor: Duration = 10.seconds,
)

/**
 * One browser's stream: a bounded queue that the dashboard offers to without waiting, written out by the virtual
 * thread serving the connection. A full queue drops its oldest `stats` event to make room; with none to drop, the
 * new event is lost, and a queue that stays full for `fullFor` closes the stream.
 */
internal class Stream(private val streaming: Streaming, private val now: () -> Long = System::nanoTime) {
    private val lock = ReentrantLock()
    private val arrived = lock.newCondition()
    private val queue = ArrayDeque<Event>()
    private var fullSince: Long? = null
    private var out: OutputStream? = null

    @Volatile
    var open: Boolean = true
        private set

    /** Queues [event], or makes room for it; false once the stream is closed. */
    fun offer(event: Event): Boolean = lock.withLock {
        if (!open) return false
        val stale = queue.indexOfFirst { it.type == STATS }
        when {
            queue.size < streaming.capacity -> fullSince = null
            stale >= 0 -> queue.removeAt(stale)
            else -> return full()
        }
        queue.addLast(event)
        arrived.signal()
        true
    }

    private fun full(): Boolean {
        val since = fullSince ?: now().also { fullSince = it }
        if ((now() - since).nanoseconds >= streaming.fullFor) close()
        return open
    }

    /** The events waiting now, oldest first, and none left behind. */
    fun drain(): List<Event> = lock.withLock { queue.toList().also { queue.clear() } }

    /** Writes events to [to] as they arrive, or a heartbeat when idle, until closed or a write fails. */
    fun write(to: OutputStream) {
        lock.withLock { out = to }
        try {
            while (open) {
                val next = lock.withLock {
                    if (queue.isEmpty()) arrived.await(streaming.heartbeat.inWholeNanoseconds, TimeUnit.NANOSECONDS)
                    queue.removeFirstOrNull()
                }
                if (open) {
                    to.write((next?.frame() ?: HEARTBEAT).encodeToByteArray())
                    to.flush()
                }
            }
        } catch (gone: IOException) {
            logDebug("a stream's browser has gone: ${gone.message}")
            close()
        }
    }

    /**
     * Ends the stream. The connection is closed on a thread of its own, since closing a chunked body writes to a
     * socket that a stalled browser may not be reading, and the dashboard that offered must not wait on it.
     */
    fun close() = lock.withLock {
        open = false
        arrived.signalAll()
        out?.let { stream -> Thread.ofVirtual().start { closeQuietly(stream) } }
    }
}

/** Every open stream on this node, which the dashboard fans each event out to. */
internal class Hub {
    private val streams = CopyOnWriteArraySet<Stream>()
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()

    val size: Int get() = streams.size

    fun publish(event: Event) = streams.forEach { stream -> if (!stream.offer(event)) leave(stream) }

    fun join(stream: Stream) = change { streams += stream }

    fun leave(stream: Stream) = change {
        streams -= stream
        stream.close()
    }

    private fun change(what: () -> Unit) = lock.withLock {
        what()
        changed.signalAll()
    }

    /** Waits up to [within] until the number of streams satisfies [until]; whether it did. */
    fun await(within: Duration, until: (Int) -> Boolean): Boolean = lock.withLock {
        var left = within.inWholeNanoseconds
        while (!until(streams.size)) {
            if (left <= 0) return false
            left = changed.awaitNanos(left)
        }
        true
    }
}

private fun closeQuietly(stream: OutputStream) {
    try {
        stream.close()
    } catch (gone: IOException) {
        logDebug("a stream's browser had closed it already: ${gone.message}")
    }
}

/** `GET` a stream of [hub]'s events: `text/event-stream`, written on the exchange's own virtual thread. */
internal fun streamOf(hub: Hub, streaming: Streaming) = HttpHandler { exchange ->
    exchange.use {
        exchange.responseHeaders.add("Content-Type", "text/event-stream")
        exchange.responseHeaders.add("Cache-Control", "no-cache")
        exchange.sendResponseHeaders(200, 0)
        // The headers out now, not with the first event: a client, EventSource included, waits for them before it
        // counts the stream as open, and JDK 25's server holds them until the body is flushed.
        exchange.responseBody.flush()
        val stream = Stream(streaming)
        hub.join(stream)
        try {
            stream.write(exchange.responseBody)
        } finally {
            hub.leave(stream)
        }
    }
}
