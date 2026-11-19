package dev.surefold.tenancy.crypto

import dev.surefold.Clock
import dev.surefold.HandoffDecision
import dev.surefold.Resource
import dev.surefold.Shared
import dev.surefold.SingleFlight
import dev.surefold.SystemClock
import dev.surefold.checkNotInTransaction
import dev.surefold.clock
import dev.surefold.fail.Either
import dev.surefold.fail.flatMap
import dev.surefold.fail.map
import dev.surefold.fail.right
import dev.surefold.onThrow
import dev.surefold.resource
import dev.surefold.resourceScope
import dev.surefold.surefoldLocal
import dev.surefold.use
import java.time.Instant
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration

/**
 * Unwrapped data keys in process memory, so a request does not wait on the KMS. A key is unwrapped again once it is
 * [soft] old; while the KMS is unavailable it is still used until it is [hard] old, and never after. Past [tenants]
 * owners, the least recently used owner's keys go. However many threads ask for one key, one unwraps it, through
 * `SingleFlight`. A key that goes is zeroed; each caller is answered a copy of its own, so a key evicted under a caller
 * is never zeroed under it.
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
    private val flights = SingleFlight<Slot, KeyError, DataKey>("surefold.tenancy.crypto.keys")
    private val recent = LinkedHashMap<KeyOwner, Unit>(16, 0.75f, true)

    /** The key in [slot], unwrapped by [load] when it is missing or [soft] old. */
    internal fun get(slot: Slot, load: () -> Either<KeyError, DataKey>): Either<KeyError, DataKey> {
        touched(slot.owner)?.let(::evict)
        val cached = held[slot]
        if (cached != null && clock.get().now() < cached.at + soft) return cached.key.copy().right()
        // The flight answers a copy no slot holds, so an eviction cannot zero it before each caller takes its own.
        return when (val shared = flights.run(slot) { unwrapped(slot, load) }) {
            is Either.Right -> shared.value.copy().right()
            is Either.Left -> shared
        }
    }

    // Under the flight for [slot]: the key again if another caller's flight refreshed it meanwhile, else [load]'s.
    private fun unwrapped(slot: Slot, load: () -> Either<KeyError, DataKey>): Either<KeyError, DataKey> {
        val now = clock.get().now()
        val cached = held[slot]
        if (cached != null && now < cached.at + soft) return cached.key.copy().right()
        return when (val loaded = load()) {
            is Either.Right -> loaded.value.copy().right().also { held[slot] = Held(loaded.value, now) }

            is Either.Left ->
                if (cached != null && loaded.value is KeyError.Unavailable && now < cached.at + hard) {
                    cached.key.copy().right()
                } else {
                    loaded.also { if (cached != null) drop(slot) }
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

/** Pending releases belonging to this cache's ownership protocol. */
internal class KeyCacheLedger {
    private class ReleaseIntent(val action: () -> Unit)
    data class LoadKey(val slot: KeyCache.Slot, val identity: KeyIdentity?, val epoch: Any)
    class Receipt(val group: Any, val id: Any, val clock: Clock)
    sealed interface Delivery {
        class Copy(val key: DataKey) : Delivery
        class View(val view: KeyView) : Delivery
    }
    private enum class ReturnDecision { Delivered, Abort }
    private class Prepared(val id: Any, val decision: HandoffDecision<ReturnDecision>, val abort: ReleaseIntent)
    private sealed interface Source
    private class Legacy(val key: DataKey) : Source
    private class Qualified(val material: OwnedKeyMaterial) : Source
    private class SourceRef(val id: Any, val source: Source)
    private sealed interface Outcome
    private class Value(val result: Either<KeyError, SourceRef>) : Outcome
    private class Thrown(val failure: Throwable) : Outcome
    private data class Group(
        val key: LoadKey, val deadline: Instant, val members: Int = 0,
        val loader: Any? = null, val outcome: Outcome? = null, val releasing: Boolean = false,
    )
    private data class State(
        val pending: List<ReleaseIntent> = emptyList(),
        val prepared: List<Prepared> = emptyList(),
        val open: Map<LoadKey, Any> = emptyMap(),
        val groups: Map<Any, Group> = emptyMap(),
        val receipts: Map<Any, Any> = emptyMap(),
        val records: Map<Any, SourceRef> = emptyMap(),
    )

    private val state = Shared(State())
    private val active = surefoldLocal<Set<Any>> { emptySet() }

    fun deliver(
        key: LoadKey,
        capturedClock: Clock,
        deadline: Instant,
        flights: SingleFlight<Any, KeyError, Unit>,
        load: () -> Either<KeyError, DataKey>,
    ): Either<KeyError, Delivery> = DeliveryCall.direct(this, enrol(key, capturedClock, deadline), flights, load)

    internal class DeliveryCall private constructor(
        private val ledger: KeyCacheLedger,
        private val receipt: Resource<Receipt>,
        private val load: () -> Either<KeyError, DataKey>,
    ) {
        private val decision = HandoffDecision<ReturnDecision>()
        private var row: Prepared? = null

        fun prepare(flights: SingleFlight<Any, KeyError, Unit>): Either<KeyError, Delivery> = receipt.use { admitted ->
            flights.run(admitted.group) { ledger.settle(admitted, load) }.flatMap {
                ledger.replay(checkNotNull(ledger.state.peek().groups[admitted.group]?.outcome)).flatMap { ref ->
                    when (val held = ref.source) {
                        is Legacy -> copy(held.key)
                        is Qualified -> held.material.borrow(admitted.clock, ::register).map { Delivery.View(it) }
                    }
                }
            }
        }

        private fun copy(key: DataKey): Either<KeyError, Delivery> = resourceScope {
            var raw: ByteArray? = null
            onRelease { _ -> raw?.fill(0) }
            val bytes = key.material.copyOf()
            raw = bytes
            register { bytes.fill(0) }
            raw = null
            Either.Right(Delivery.Copy(DataKey(key.owner, key.purpose, key.version, bytes)))
        }

        private fun register(closeExact: () -> Unit) {
            row = Prepared(Any(), decision, ReleaseIntent(closeExact))
            ledger.registerPrepared(checkNotNull(row))
        }

        fun chooseAbort(): Boolean {
            decision.tryComplete(ReturnDecision.Abort)
            return row != null
        }

        fun abort() = ledger.abortPrepared(checkNotNull(row))

        fun delivered() = decision.tryComplete(ReturnDecision.Delivered)

        companion object {
            fun direct(
                ledger: KeyCacheLedger, receipt: Resource<Receipt>,
                flights: SingleFlight<Any, KeyError, Unit>, load: () -> Either<KeyError, DataKey>,
            ): Either<KeyError, Delivery> {
                val call = DeliveryCall(ledger, receipt, load)
                val answer = resourceScope {
                    var ready = false
                    onRelease { _ -> if (!ready && call.chooseAbort()) call.abort() }
                    call.prepare(flights).also { ready = it is Either.Right }
                }
                if (answer is Either.Right) call.delivered()
                return answer
            }

            fun run(
                callers: KeyCallers, ledger: KeyCacheLedger, receipt: Resource<Receipt>,
                load: () -> Either<KeyError, DataKey>,
            ): Either<KeyError, Delivery> = callers.deliver(DeliveryCall(ledger, receipt, load))
        }
    }

    private fun registerPrepared(row: Prepared) = state.update { it.copy(prepared = it.prepared + row) }

    private fun abortPrepared(row: Prepared) = resourceScope {
        onRelease { _ ->
            val current = state.peek()
            if (current.prepared.any { it.id === row.id } || current.pending.any { it === row.abort }) {
                row.abort.action()
                acknowledge(row.abort)
            }
        }
        transferPrepared(row)
    }

    private fun transferPrepared(row: Prepared) = state.update { current ->
        if (current.prepared.none { it.id === row.id }) current
        else current.copy(
            prepared = current.prepared.filterNot { it.id === row.id }, pending = current.pending + row.abort,
        )
    }

    private fun reapPrepared(row: Prepared) = state.update { current ->
        current.copy(prepared = current.prepared.filterNot { it.id === row.id })
    }

    private fun acknowledge(intent: ReleaseIntent) = state.update { current ->
        current.copy(pending = current.pending.filterNot { it === intent })
    }

    fun owe(action: () -> Unit) {
        val intent = ReleaseIntent(action)
        state.update { it.copy(pending = it.pending + intent) }
    }

    fun drain() = resourceScope {
        val snapshot = state.peek()
        val rows = snapshot.prepared.map { it to it.decision.peek() }
        rows.asReversed().forEach { (row, phase) ->
            onRelease { _ ->
                when (phase) {
                    null -> Unit
                    ReturnDecision.Delivered -> reapPrepared(row)
                    ReturnDecision.Abort -> abortPrepared(row)
                }
            }
        }
        snapshot.pending.asReversed().forEach { intent ->
            onRelease { _ ->
                intent.action()
                acknowledge(intent)
            }
        }
    }

    fun drainAfterClose(runPhase: (action: () -> Unit, committed: () -> Boolean) -> Boolean) {
        val snapshot = state.peek()
        val rows = snapshot.prepared.map { it to it.decision.peek() }
        rows.forEach { (row, decision) ->
            val removed = { state.peek().prepared.none { it.id === row.id } }
            when (decision) {
                null -> Unit

                ReturnDecision.Delivered -> runPhase({ if (!removed()) reapPrepared(row) }, removed)

                ReturnDecision.Abort -> {
                    if (runPhase({ if (!removed()) transferPrepared(row) }, removed)) closeIntent(row.abort, runPhase)
                }
            }
        }
        snapshot.pending.forEach { closeIntent(it, runPhase) }
    }

    private fun closeIntent(intent: ReleaseIntent, runPhase: (() -> Unit, () -> Boolean) -> Boolean) {
        val acknowledged = { state.peek().pending.none { it === intent } }
        // The loop for Ack begins only after the exact action has finished.
        if (runPhase({ if (!acknowledged()) intent.action() }, acknowledged)) {
            runPhase({ if (!acknowledged()) acknowledge(intent) }, acknowledged)
        }
    }

    fun enrol(key: LoadKey, clock: Clock, deadline: Instant): Resource<Receipt> = resource {
        val id = Any()
        val candidate = Any()
        val inherited = active.get()
        onRelease { _ -> finishReceipt(id) }
        val group = state.modify { current ->
            val token = current.open[key] ?: candidate
            check(token !in inherited) { "recursive key load" }
            val held = current.groups[token] ?: Group(key, deadline)
            current.copy(
                open = current.open + (key to token), receipts = current.receipts + (id to token),
                groups = current.groups + (token to held.copy(members = held.members + 1)),
            ) to token
        }
        Receipt(group, id, clock)
    }

    // A flight must replay the original throwable, including cancellation.
    @Suppress("TooGenericExceptionCaught")
    fun settle(receipt: Receipt, load: () -> Either<KeyError, DataKey>): Either<KeyError, Unit> =
        active.locally(active.get() + receipt.group) {
            resourceScope {
                val token = Any()
                val id = Any()
                var failed: Throwable? = null
                var transferred: DataKey? = null
                onRelease { _ -> finishLoader(receipt.group, token, failed) }
                onRelease { _ -> if (state.peek().records[id] == null) transferred?.material?.fill(0) }
                try {
                    val group = state.modify { current ->
                        val held = current.groups[receipt.group] ?: retired()
                        if (held.outcome != null) current to held
                        else {
                            check(held.loader == null) { "the key load is already running" }
                            current.copy(groups = current.groups + (receipt.group to held.copy(loader = token))) to held
                        }
                    }
                    group.outcome?.let { return@resourceScope replay(it).map { Unit } }
                    val loaded = load()
                    if (loaded is Either.Right) transferred = loaded.value
                    val result = loaded.flatMap { key ->
                        val identity = group.key.identity
                        if (identity == null) Either.Right(SourceRef(id, Legacy(key)))
                        else OwnedKeyMaterial.take(identity, key, group.deadline) {
                            state.update { it.copy(records = it.records - id) }
                        }.map { SourceRef(id, Qualified(it)) }
                    }
                    state.update { current ->
                        val held = checkNotNull(current.groups[receipt.group])
                        val source = result.getOrNull()
                        current.copy(
                            open = current.open.filterValues { it !== receipt.group },
                            groups = current.groups + (receipt.group to held.copy(outcome = Value(result))),
                            records = if (source == null) current.records else current.records + (id to source),
                        )
                    }
                    result.map { Unit }
                } catch (failure: Throwable) {
                    failed = failure
                    throw failure
                }
            }
        }

    private fun finishReceipt(id: Any) = resourceScope {
        val group = state.peek().receipts[id] ?: return@resourceScope
        val intent = ReleaseIntent { releaseGroup(group) }
        onRelease { _ -> drain() }
        state.update { current ->
            val held = current.groups[group] ?: return@update current
            if (current.receipts[id] !== group) return@update current
            collect(
                current.copy(
                    receipts = current.receipts - id,
                    groups = current.groups + (group to held.copy(members = held.members - 1)),
                ),
                group, intent,
            )
        }
    }

    private fun finishLoader(id: Any, token: Any, failure: Throwable?) = resourceScope {
        val intent = ReleaseIntent { releaseGroup(id) }
        onRelease { _ -> drain() }
        state.update { current ->
            val held = current.groups[id] ?: return@update current
            val unclaimedFailure = held.loader == null && held.outcome == null && failure != null
            if (held.loader !== token && !unclaimedFailure) {
                return@update current
            }
            val outcome = held.outcome ?: Thrown(checkNotNull(failure))
            collect(
                current.copy(
                    open = current.open.filterValues { it !== id },
                    groups = current.groups + (id to held.copy(loader = null, outcome = outcome)),
                ),
                id, intent,
            )
        }
    }

    private fun collect(current: State, id: Any, intent: ReleaseIntent): State {
        val group = checkNotNull(current.groups[id])
        if (group.members != 0 || group.loader != null || group.releasing) return current
        if ((group.outcome as? Value)?.result?.getOrNull() == null) {
            return current.copy(open = current.open.filterValues { it !== id }, groups = current.groups - id)
        }
        return current.copy(
            groups = current.groups + (id to group.copy(releasing = true)), pending = current.pending + intent,
        )
    }

    private fun releaseGroup(id: Any) {
        val ref = ((state.peek().groups[id]?.outcome as? Value)?.result?.getOrNull()) ?: return
        when (val source = ref.source) {
            is Legacy -> source.key.material.fill(0)
            is Qualified -> source.material.forget()
        }
        state.update { current ->
            current.copy(
                groups = current.groups - id,
                records = if (ref.source is Legacy) current.records - ref.id else current.records,
            )
        }
    }

    private fun replay(outcome: Outcome): Either<KeyError, SourceRef> = when (outcome) {
        is Value -> outcome.result
        is Thrown -> throw outcome.failure
    }

    private fun retired(): Nothing = throw CancellationException("the key load is no longer admitted")
}

/** Admission and the final unwind boundary of this cache's callers. */
internal class KeyCallers(private val flights: SingleFlight<Any, KeyError, Unit>) {
    private class Ticket {
        val finished = HandoffDecision<Unit>()
    }

    private data class CallerState(
        val closed: Boolean = false,
        val tickets: List<Ticket> = emptyList(),
    )

    private class Invocation {
        val failures = mutableListOf<Throwable>()
        val observed = mutableListOf<Throwable>()
        var admitted = false

        fun record(failure: Throwable) {
            if (failures.none { it === failure }) failures.add(failure)
            val visited = mutableListOf<Throwable>()
            fun visit(node: Throwable) {
                if (visited.any { it === node }) return
                visited.add(node)
                if (observed.none { it === node }) observed.add(node)
                node.suppressed.forEach(::visit)
            }
            visit(failure)
        }

        fun refresh() {
            failures.toList().forEach(::record)
        }

        fun assembled(primary: Throwable? = failures.firstOrNull()): Throwable? {
            if (primary == null) return null
            failures.forEach { later ->
                val seen = mutableListOf<Throwable>()
                if (!contains(primary, later, seen)) primary.addSuppressed(later)
            }
            return primary
        }

        private fun contains(root: Throwable, target: Throwable, seen: MutableList<Throwable>): Boolean {
            if (root === target) return true
            if (seen.any { it === root }) return false
            seen.add(root)
            return root.suppressed.any { contains(it, target, seen) }
        }
    }

    private enum class CloseStage { FENCE, STOP, OBSERVE, REAP }

    private inner class Closing {
        val outcome = Invocation()
        var interrupted = Thread.interrupted()
        private var fenced = false
        private var observing = false
        private var remaining = emptyList<Ticket>()
        private var terminal = emptyList<Ticket>()

        fun sample() {
            outcome.refresh()
            interrupted = Thread.interrupted() || interrupted || outcome.observed.any { it is InterruptedException }
        }

        fun phase(stage: CloseStage) {
            runPhase({
                when (stage) {
                    CloseStage.FENCE -> fence()
                    CloseStage.STOP -> flights.closeAndJoin()
                    CloseStage.OBSERVE -> observe()
                    CloseStage.REAP -> reap()
                }
            }, {
                when (stage) {
                    CloseStage.FENCE -> {
                        val current = state.peek()
                        fenced = current.closed
                        remaining = current.tickets
                        fenced
                    }

                    CloseStage.REAP -> state.peek().tickets.none { ticket -> terminal.any { it === ticket } }

                    CloseStage.STOP, CloseStage.OBSERVE -> false
                }
            })
        }

        fun drain(ledger: KeyCacheLedger) = ledger.drainAfterClose(::runPhase)

        // A mixed fault ends its exact phase even when the state change committed.
        @Suppress("TooGenericExceptionCaught")
        private fun runPhase(action: () -> Unit, committed: () -> Boolean): Boolean {
            while (true) {
                val attempt = Invocation()
                var done = false
                try {
                    action()
                    done = true
                } catch (failure: Throwable) {
                    attempt.record(failure)
                    outcome.record(failure)
                    try {
                        done = committed()
                    } catch (reconciliation: Throwable) {
                        attempt.record(reconciliation)
                        outcome.record(reconciliation)
                    }
                } finally {
                    sample()
                }
                attempt.refresh()
                if (attempt.observed.any { it !is InterruptedException }) return false
                if (done) return true
            }
        }

        private fun fence() {
            remaining = state.modify { current -> current.copy(closed = true) to current.tickets }
            fenced = true
        }

        // A failed native marker read is retained without preventing attempts of the other captured markers.
        @Suppress("TooGenericExceptionCaught")
        private fun observe() {
            if (!observing) {
                if (!fenced) remaining = state.peek().tickets
                observing = true
            }
            while (remaining.isNotEmpty()) {
                remaining = remaining.filter { ticket ->
                    val attempt = Invocation()
                    try {
                        if (ticket.finished.peek() == null) true
                        else {
                            terminal = terminal + ticket
                            false
                        }
                    } catch (failure: Throwable) {
                        attempt.record(failure)
                        outcome.record(failure)
                        attempt.refresh()
                        attempt.observed.all { it is InterruptedException }
                    } finally {
                        sample()
                    }
                }
                if (remaining.isNotEmpty()) {
                    try {
                        SystemClock.sleep(10.milliseconds)
                    } finally {
                        sample()
                    }
                }
            }
        }

        private fun reap() {
            if (terminal.isNotEmpty()) state.update { current ->
                current.copy(tickets = current.tickets.filterNot { ticket -> terminal.any { it === ticket } })
            }
        }
    }

    fun closeAndAwait() = closeAndAwait(null)

    // Capture inside the owner binding before restoration can mask it; outer capture retains restoration too.
    @Suppress("TooGenericExceptionCaught")
    fun closeAndAwait(ledger: KeyCacheLedger?) {
        checkNotInTransaction()
        check(identity !in active.get()) { "a key cache caller cannot close its owner" }
        val closing = Closing()
        var boundaryFailure: Throwable? = null
        try {
            try {
                active.locally(active.get() + identity) {
                    try {
                        CloseStage.entries.forEach(closing::phase)
                        ledger?.let(closing::drain)
                    } catch (failure: Throwable) {
                        closing.outcome.record(failure)
                    }
                }
            } catch (restoration: Throwable) {
                closing.outcome.record(restoration)
                boundaryFailure = restoration
            }
            closing.sample()
            val primary = closing.outcome.observed.firstOrNull { it !is InterruptedException }
                ?: boundaryFailure
            val failure = closing.outcome.assembled(primary)
            if (failure != null) throw failure
        } finally {
            closing.sample()
            if (closing.interrupted) Thread.currentThread().interrupt()
        }
    }

    private val identity = Any()
    private val active = surefoldLocal<Set<Any>> { emptySet() }
    private val state = Shared(CallerState())

    private inner class AcceptedCall {
        val ticket = Ticket()
        val invocation = Invocation()

        fun <A> run(body: () -> A): A = withinOwner {
            if (!admit(ticket, invocation)) throw checkNotNull(invocation.assembled())
            body()
        }

        fun abort(call: KeyCacheLedger.DeliveryCall) {
            if (call.chooseAbort()) withinOwner(call::abort)
        }

        // The failed cleanup must carry the caller's original failure, with distinct diagnostics.
        @Suppress("ThrowingExceptionFromFinally")
        fun abortAfterFailure(call: KeyCacheLedger.DeliveryCall, primary: Throwable) {
            var abortFailed = false
            try {
                onThrow({ cleanup ->
                    abortFailed = true
                    invocation.record(cleanup)
                }) { abort(call) }
            } finally {
                if (abortFailed) throw checkNotNull(invocation.assembled(primary))
            }
        }

        private fun <A> withinOwner(body: () -> A): A = resourceScope {
            install({ active.attach(active.get() + identity) }) { binding, _ -> binding.detach() }
            body()
        }
    }

    fun call(body: () -> Unit) {
        checkNotInTransaction()
        val accepted = AcceptedCall()
        try {
            accepted.run(body)
        } finally {
            accepted.ticket.finished.tryComplete(Unit)
        }
    }

    fun deliver(call: KeyCacheLedger.DeliveryCall): Either<KeyError, KeyCacheLedger.Delivery> {
        checkNotInTransaction()
        val accepted = AcceptedCall()
        try {
            val answer = onThrow({ primary -> accepted.abortAfterFailure(call, primary) }) {
                accepted.run { call.prepare(flights) }
            }
            when (answer) {
                is Either.Left -> accepted.abort(call)
                is Either.Right -> call.delivered()
            }
            return answer
        } finally {
            accepted.ticket.finished.tryComplete(Unit)
        }
    }

    // A committed observer failure cannot undo acceptance or license body entry.
    @Suppress("TooGenericExceptionCaught")
    private fun admit(ticket: Ticket, invocation: Invocation): Boolean {
        val terminal = state.peek().tickets.filter { it.finished.peek() != null }
        try {
            invocation.admitted = state.modify { current ->
                if (current.closed) current to false
                else current.copy(
                    tickets = current.tickets.filterNot { old -> terminal.any { it === old } } + ticket,
                ) to true
            }
        } catch (failure: Throwable) {
            invocation.record(failure)
            invocation.admitted = state.peek().tickets.any { it === ticket }
            return false
        }
        check(invocation.admitted) { "the key cache is closed" }
        return true
    }
}
