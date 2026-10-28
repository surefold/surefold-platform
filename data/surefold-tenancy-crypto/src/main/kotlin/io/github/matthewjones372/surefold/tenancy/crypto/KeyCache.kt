package io.github.matthewjones372.surefold.tenancy.crypto

import io.github.matthewjones372.surefold.clock
import io.github.matthewjones372.surefold.fail.Either
import io.github.matthewjones372.surefold.fail.right
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration

/**
 * Unwrapped data keys in process memory, so a request does not wait on the KMS. A key is unwrapped again once it is
 * [soft] old; while the KMS is unavailable it is still used until it is [hard] old, and never after. Past [tenants]
 * owners, the least recently used owner's keys go. However many threads ask for one key, one unwraps it. A key that
 * goes is zeroed; each caller is answered a copy of its own, so a key evicted under a caller is never zeroed under it.
 */
class KeyCache(
    val soft: Duration = 15.minutes,
    val hard: Duration = 60.minutes,
    val tenants: Int = 1_000,
) {
    init {
        require(soft <= hard) { "a key is refreshed after $soft, so it cannot be refused sooner, at $hard" }
        require(tenants >= 1) { "the cache holds at least one owner's keys, not $tenants" }
    }

    /** One key: an owner's [purpose] at [version], or its active one when [version] is null. */
    internal data class Slot(val owner: KeyOwner, val purpose: KeyPurpose, val version: Int?)

    private class Held(val key: DataKey, val at: Instant)

    private val held = ConcurrentHashMap<Slot, Held>()
    private val loading = ConcurrentHashMap<Slot, ReentrantLock>()
    private val recent = LinkedHashMap<KeyOwner, Unit>(16, 0.75f, true)

    /** The key in [slot], unwrapped by [load] when it is missing or [soft] old. */
    internal fun get(slot: Slot, load: () -> Either<KeyError, DataKey>): Either<KeyError, DataKey> {
        touched(slot.owner)?.let(::evict)
        return loading.computeIfAbsent(slot) { ReentrantLock() }.withLock {
            val now = clock.get().now()
            val cached = held[slot]
            if (cached != null && now < cached.at + soft) return cached.key.copy().right()
            when (val loaded = load()) {
                is Either.Right -> loaded.value.copy().right().also { held[slot] = Held(loaded.value, now) }

                is Either.Left ->
                    if (cached != null && loaded.value is KeyError.Unavailable && now < cached.at + hard) {
                        cached.key.copy().right()
                    } else {
                        loaded.also { if (cached != null) drop(slot) }
                    }
            }
        }
    }

    private fun DataKey.copy() = DataKey(owner, purpose, version, material.copyOf())

    /** Drops every key of [owner]'s at once, zeroed: after a rotation, a retirement or a shredding. */
    fun evict(owner: KeyOwner) {
        held.keys.filter { it.owner == owner }.forEach(::drop)
        synchronized(recent) { recent.remove(owner) }
    }

    /** Marks [owner] as used, and answers the owner pushed out past [tenants], if one was. */
    private fun touched(owner: KeyOwner): KeyOwner? = synchronized(recent) {
        recent[owner] = Unit
        if (recent.size > tenants) recent.keys.first() else null
    }

    private fun drop(slot: Slot) {
        held.remove(slot)?.key?.material?.fill(0)
    }

    private operator fun Instant.plus(duration: Duration): Instant = plus(duration.toJavaDuration())
}
