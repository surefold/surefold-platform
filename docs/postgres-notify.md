# Postgres notifications

`surefold-postgres-notify` turns Postgres's `LISTEN` into a surefold `Stream`. A service whose pages should update
live has a trigger, or its own code, call `pg_notify` in the changing transaction. Postgres delivers each notification
after commit, in commit order, to every listener, and never delivers one whose transaction rolled back.

```kotlin
dependencies {
    implementation("io.github.matthewjones372:surefold-postgres-notify:0.15.0")
}
```

## Listening, and sending

<!-- postgres-notify -->
```kotlin
import io.github.matthewjones372.surefold.stream.Forks
import io.github.matthewjones372.surefold.stream.Hub
import io.github.matthewjones372.surefold.stream.HubRefused
import io.github.matthewjones372.surefold.stream.Running
import io.github.matthewjones372.surefold.stream.map
import io.github.matthewjones372.surefold.stream.publishTo
import io.github.matthewjones372.surefold.stream.runFold
import io.github.matthewjones372.surefold.stream.start
import java.sql.Connection
import javax.sql.DataSource

sealed interface LiveEvent {
    data class Booked(val bookingId: String) : LiveEvent

    data object Resync : LiveEvent
}

/** One listener for the process, on a connection of its own, fanned out to every open page through [hub]. */
fun liveEvents(listenSource: DataSource, hub: Hub<LiveEvent>): Running<HubRefused, Unit> =
    PostgresNotifications.listen(listenSource, channels = setOf("bookings_live"))
        .map { notice ->
            when (notice) {
                is Notice.Received -> LiveEvent.Booked(bookingId = notice.payload)
                Notice.Gap -> LiveEvent.Resync
            }
        }
        .publishTo(hub)
        .runFold(Unit) { _, _ -> }
        .start(Forks())

/** Sent in the booking's own transaction, so no page hears of a booking that rolled back. */
fun booked(connection: Connection, bookingId: String) =
    PostgresNotifications.notify(connection, "bookings_live", bookingId)
```

A run of `listen` holds one connection, so `listenSource` should be a `DataSource` of its own, outside the pool. Run it
once per process and share it through a `Hub`. The connection is found to be Postgres's by `unwrap`, so no driver is
named; on another driver the run fails as it opens.

A channel is a lower-case identifier, such as `bookings_live`: `LISTEN` takes no bound parameter, and folds an unquoted
name to lower case where `pg_notify` does not. `notify` refuses a payload past 7,999 bytes. Send an id, and read the
row it names.

## Reconnecting, and a Gap

A connection that cannot be made, or is lost, is made again after the delays `reconnect` answers. By default each one
is between half and all of a delay that doubles from a second, up to thirty. Once a lost connection listens again the
stream says `Notice.Gap` before anything else: whatever was sent meanwhile is gone, so a page refetches what it shows.
A first connection that fails is retried with no `Gap`, since a page fetches at the start anyway.

`reconnect` is any `Schedule<Throwable, *>`, and a schedule that stops ends the run with the last failure. Its delays
are waited on the clock bound where `listen` is called, so a test moves them on with a `TestClock`. Each lost connection
logs a warning and counts `postgres.notify.reconnects`.

## Jobs

A `surefold-jobs` worker listens through this module on `surefold_jobs`. A job enqueued to run now wakes it before its
next poll, and so does a reconnect, since a job enqueued meanwhile was not heard. Its reconnects start at
`JobSettings.poll`.
