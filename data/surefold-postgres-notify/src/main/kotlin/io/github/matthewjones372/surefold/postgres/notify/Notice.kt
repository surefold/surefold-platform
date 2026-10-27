package io.github.matthewjones372.surefold.postgres.notify

/** What a listener hears from Postgres. */
sealed interface Notice {

    /** A notification on [channel], with the [payload] it was sent with, from the backend whose process id is [pid]. */
    data class Received(val channel: String, val payload: String, val pid: Int) : Notice
}
