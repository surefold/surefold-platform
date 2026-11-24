package dev.surefold.tenancy.crypto.bench

import dev.surefold.fail.Either
import dev.surefold.resourceScope
import dev.surefold.scope
import dev.surefold.tenancy.crypto.DataKey
import dev.surefold.tenancy.crypto.KeyCache
import dev.surefold.tenancy.crypto.KeyIdentity
import dev.surefold.tenancy.crypto.KeyOwner
import dev.surefold.tenancy.crypto.KeyPurpose
import dev.surefold.tenancy.crypto.KeyView
import java.util.Locale
import kotlin.time.TimeSource

private const val KEY_BYTES = 32
private const val NEIGHBOURS = 8
private const val WARM_UP_SHARE = 10
private const val NANOS_PER_MICRO = 1000.0
private const val MEDIAN = 0.50
private const val TAIL = 0.99

/** Times every use of one held view at each caller count, and prints a line for each. */
fun main(args: Array<String>) {
    val uses = args.getOrNull(0)?.toInt() ?: 200_000
    val cache = KeyCache()
    val key = KeyIdentity(KeyOwner.Platform, KeyPurpose.Enc, 1, byteArrayOf(1))
    resourceScope {
        onRelease { _ -> cache.closeAndJoin() }
        val leased = cache.lease(key) {
            Either.Right(DataKey(key.owner, key.purpose, key.version, ByteArray(KEY_BYTES) { it.toByte() }))
        }
        val view = (leased as Either.Right).value
        onRelease { _ -> view.close() }
        timed(view, callers = NEIGHBOURS, uses = uses / WARM_UP_SHARE)
        listOf(1, NEIGHBOURS).forEach { callers -> println(line(callers, timed(view, callers, uses))) }
    }
}

// The callback reads one byte, so what is timed is the view's own admission, copy and wipe.
private fun timed(view: KeyView, callers: Int, uses: Int): List<Long> = scope {
    (1..callers).map {
        async {
            LongArray(uses) {
                val started = TimeSource.Monotonic.markNow()
                view.using { key -> key[0] }
                started.elapsedNow().inWholeNanoseconds
            }
        }
    }.flatMap { it.await().asList() }.sorted()
}

private fun line(callers: Int, nanos: List<Long>): String {
    fun at(share: Double) = nanos[((nanos.size - 1) * share).toInt()] / NANOS_PER_MICRO
    return String.format(
        Locale.ROOT,
        "%d caller(s): p50 %.2f us, p99 %.2f us, max %.0f us over %d uses",
        callers, at(MEDIAN), at(TAIL), at(1.0), nanos.size,
    )
}
