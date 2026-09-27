package io.github.matthewjones372.lark.bank

import io.github.matthewjones372.lark.actor.behaviour
import io.github.matthewjones372.lark.actor.remote.Node
import io.github.matthewjones372.lark.actor.stay
import io.github.matthewjones372.lark.cluster.Member
import io.github.matthewjones372.lark.cluster.MemberEvent
import io.github.matthewjones372.lark.cluster.Status
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration

/** The members one node has heard of, by their events: who is in, and who some member cannot reach. */
internal data class Seen(val members: Map<Node, Member> = emptyMap(), val unreachable: Set<Node> = emptySet()) {
    fun after(event: MemberEvent): Seen = when (event) {
        is MemberEvent.Up, is MemberEvent.Downed -> copy(members = members + (event.member.node to event.member))
        is MemberEvent.Unreachable -> copy(unreachable = unreachable + event.member.node)
        is MemberEvent.Reachable -> copy(unreachable = unreachable - event.member.node)
        is MemberEvent.Removed -> Seen(members - event.member.node, unreachable - event.member.node)
    }

    val up: List<Member> get() = members.values.filter { it.status == Status.Up }.sortedBy { it.upNumber }
}

/**
 * What a node's subscriber to its cluster has seen, for code outside the actors to wait on. `Cluster.await` is the
 * library's own and internal, so the bank keeps its own copy of the view, built from the events it is told.
 */
internal class Members {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()

    @Volatile
    var seen: Seen = Seen()
        private set

    /** The subscriber: it keeps [seen] and hands each event, with what it made of it, to [heard]. */
    fun subscriber(heard: (MemberEvent, Seen) -> Unit) = behaviour<MemberEvent, Unit>(Unit) { _, _, event ->
        val next = lock.withLock {
            seen = seen.after(event)
            changed.signalAll()
            seen
        }
        heard(event, next)
        stay()
    }

    /** Waits up to [within] until what has been seen satisfies [until]; whether it did. */
    fun await(within: Duration, until: (Seen) -> Boolean): Boolean = lock.withLock {
        var left = within.inWholeNanoseconds
        while (!until(seen)) {
            if (left <= 0) return false
            left = changed.awaitNanos(left)
        }
        true
    }
}
