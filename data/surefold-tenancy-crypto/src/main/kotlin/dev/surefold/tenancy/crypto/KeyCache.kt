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
import dev.surefold.onThrow
import dev.surefold.resource
import dev.surefold.resourceScope
import dev.surefold.surefoldLocal
import dev.surefold.use
import java.time.Instant
import java.util.concurrent.CancellationException
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

    private val callers = KeyCallers(SingleFlight("surefold.tenancy.crypto.keys"))
    private val ledger = KeyCacheLedger(tenants, soft, hard, callers)

    /** The key in [slot], unwrapped by [load] when it is missing or [soft] old. */
    internal fun get(slot: Slot, load: () -> Either<KeyError, DataKey>): Either<KeyError, DataKey> =
        KeyCacheLedger.DeliveryCall.copy(callers, ledger, ledger.enrol(slot, clock.get()), load)

    fun lease(identity: KeyIdentity, load: () -> Either<KeyError, DataKey>): Either<KeyError, KeyView> =
        KeyCacheLedger.DeliveryCall.view(callers, ledger, ledger.enrol(identity, clock.get()), load)

    fun revoke(owner: KeyOwner) = ledger.revoke(owner)

    fun revokeAll() = ledger.revoke(null)

    fun closeAndJoin() = callers.closePublic(ledger)

    /** Drops every retained key of [owner]'s. Existing copies remain independently owned. */
    fun evict(owner: KeyOwner) = ledger.evict(owner)
}

private operator fun Instant.plus(duration: Duration): Instant = plus(duration.toJavaDuration())

private fun closedKeyCache(): Nothing = error("the key cache is closed")

private val viewDenied = KeyError.Denied("the key view is unavailable")
private val viewExpired = KeyError.Unavailable("the key view has expired")

/** Immutable retention decisions. The ledger owns the sources behind these opaque IDs. */
internal class KeyRetention private constructor(
    private val capacity: Int,
    private val ticket: Long,
    private val owners: Map<KeyOwner, Owner>,
) {
    constructor(capacity: Int) : this(capacity, 0L, emptyMap()) {
        require(capacity > 0)
    }

    class Admission(
        val slot: KeyCache.Slot,
        val identity: KeyIdentity?,
        val epoch: Any,
        val ticket: Long,
        val captured: Record?,
    )

    class Record(
        val slot: KeyCache.Slot,
        val identity: KeyIdentity?,
        val epoch: Any,
        val ticket: Long,
        val sourceId: Any,
        val loadedAt: Instant,
    )

    data class Admitted(val policy: KeyRetention, val admission: Admission, val displaced: List<Record>)
    data class Publication(val policy: KeyRetention, val accepted: Boolean, val displaced: Record?)
    data class Removal(val policy: KeyRetention, val displaced: List<Record>)

    private data class Owner(val epoch: Any, val slots: Map<KeyCache.Slot, Retained> = emptyMap())
    private data class Retained(val watermark: Long, val record: Record?)

    fun admit(slot: KeyCache.Slot, freshEpoch: Any): Admitted = admit(slot, null, freshEpoch)

    fun admit(identity: KeyIdentity, freshEpoch: Any): Admitted =
        admit(KeyCache.Slot(identity.owner, identity.purpose, identity.version), identity, freshEpoch)

    private fun admit(slot: KeyCache.Slot, identity: KeyIdentity?, freshEpoch: Any): Admitted {
        val owner = owners[slot.owner] ?: Owner(freshEpoch)
        val touched = owners - slot.owner + (slot.owner to owner)
        val evicted = if (touched.size > capacity) touched.entries.first() else null
        val remaining = if (evicted == null) touched else touched - evicted.key
        val nextTicket = Math.incrementExact(ticket)
        val captured = owner.slots[slot]?.record?.takeIf { it.identity == identity }
        return Admitted(
            KeyRetention(capacity, nextTicket, remaining),
            Admission(slot, identity, owner.epoch, nextTicket, captured),
            evicted?.value?.slots?.values.orEmpty().mapNotNull { it.record },
        )
    }

    fun publish(admission: Admission, sourceId: Any, loadedAt: Instant): Publication {
        val slot = admission.slot
        val owner = owners[slot.owner]
        if (owner == null || owner.epoch !== admission.epoch) return Publication(this, false, null)
        val prior = owner.slots[slot]
        if (prior != null && admission.ticket <= prior.watermark) return Publication(this, false, null)
        val record = Record(slot, admission.identity, admission.epoch, admission.ticket, sourceId, loadedAt)
        return Publication(
            update(slot, owner, Retained(admission.ticket, record)),
            true,
            prior?.record,
        )
    }

    fun contains(record: Record): Boolean =
        owners[record.slot.owner]?.slots?.get(record.slot)?.record === record

    fun retained(slot: KeyCache.Slot, sourceId: Any): Record? =
        owners[slot.owner]?.slots?.get(slot)?.record?.takeIf { it.sourceId === sourceId }

    fun drop(record: Record): Removal {
        if (!contains(record)) return Removal(this, emptyList())
        val owner = owners.getValue(record.slot.owner)
        val slot = owner.slots.getValue(record.slot)
        return Removal(update(record.slot, owner, slot.copy(record = null)), listOf(record))
    }

    fun forget(owner: KeyOwner): Removal {
        val held = owners[owner] ?: return Removal(this, emptyList())
        return Removal(KeyRetention(capacity, ticket, owners - owner), held.slots.values.mapNotNull { it.record })
    }

    fun forgetAll(): Removal = Removal(
        KeyRetention(capacity, ticket, emptyMap()),
        owners.values.flatMap { owner -> owner.slots.values.mapNotNull { it.record } },
    )

    private fun update(slot: KeyCache.Slot, owner: Owner, retained: Retained): KeyRetention {
        val changed = owner.copy(slots = owner.slots + (slot to retained))
        return KeyRetention(capacity, ticket, owners + (slot.owner to changed))
    }
}

/** Pending releases belonging to this cache's ownership protocol. */
internal class KeyCacheLedger(
    capacity: Int = 1_000,
    private val soft: Duration = 15.minutes,
    private val hard: Duration = 60.minutes,
    private val callers: KeyCallers? = null,
) {
    private class ReleaseIntent(val action: () -> Unit)
    data class LoadKey(val slot: KeyCache.Slot, val identity: KeyIdentity?, val epoch: Any)
    class Receipt(val group: Any, val id: Any, val clock: Clock, val closed: Boolean = false)
    sealed interface Delivery {
        class Copy(val key: DataKey) : Delivery
        class View(val view: KeyView) : Delivery
    }
    private enum class ReturnDecision { Delivered, Abort }
    private class Prepared(val id: Any, val decision: HandoffDecision<ReturnDecision>, val abort: ReleaseIntent)
    private sealed interface Source { val release: ReleaseIntent }
    private class Legacy(val key: DataKey) : Source {
        override val release = ReleaseIntent { key.material.fill(0) }
    }
    private class Qualified(val material: OwnedKeyMaterial, val deadline: Instant?) : Source {
        override val release = ReleaseIntent { material.forget() }
        val revoked = Shared(false)
        val revoke = ReleaseIntent { material.revoke() }
    }
    private class SourceRef(val id: Any, val slot: KeyCache.Slot, val source: Source)
    private sealed interface Outcome
    private class Value(val result: Either<KeyError, SourceRef>, val coldExpired: Boolean = false) : Outcome
    private class Thrown(val failure: Throwable) : Outcome
    private data class Group(
        val key: LoadKey, val deadline: Instant, val members: Int = 0,
        val loader: Any? = null, val outcome: Outcome? = null, val releasing: Boolean = false,
        val admission: KeyRetention.Admission? = null, val revoked: Boolean = false,
    ) {
        val source get() = ((outcome as? Value)?.result as? Either.Right)?.value
        val captured get() = admission?.captured
    }
    private data class State(
        val retention: KeyRetention,
        val closed: Boolean = false,
        val pending: List<ReleaseIntent> = emptyList(),
        val prepared: List<Prepared> = emptyList(),
        val open: Map<LoadKey, Any> = emptyMap(),
        val groups: Map<Any, Group> = emptyMap(),
        val receipts: Map<Any, Any> = emptyMap(),
        val records: Map<Any, SourceRef> = emptyMap(),
    )

    private val state = Shared(State(KeyRetention(capacity)))
    private val active = surefoldLocal<Set<Any>> { emptySet() }

    fun deliver(
        key: LoadKey,
        capturedClock: Clock,
        deadline: Instant,
        flights: SingleFlight<Any, KeyError, Unit>,
        load: () -> Either<KeyError, DataKey>,
    ): Either<KeyError, Delivery> = DeliveryCall.direct(this, enrol(key, capturedClock, deadline), flights, load)

    internal class DeliveryCall<A> private constructor(
        private val ledger: KeyCacheLedger,
        private val receipt: Resource<Receipt>,
        private val load: () -> Either<KeyError, DataKey>,
        val refuse: () -> Either<KeyError, A> = ::closedKeyCache,
        private val project: (Delivery) -> A,
    ) {
        private val decision = HandoffDecision<ReturnDecision>()
        private var row: Prepared? = null

        fun prepare(flights: SingleFlight<Any, KeyError, Unit>): Either<KeyError, A> = receipt.use { admitted ->
            if (admitted.closed) return@use refuse()
            ledger.awaitSource(admitted, flights, load).flatMap { ref ->
                when (val held = ref.source) {
                    is Legacy -> copy(held.key)
                    is Qualified -> borrow(ref, held, admitted.clock)
                }
            }.map(project)
        }

        private fun borrow(ref: SourceRef, held: Qualified, clock: Clock): Either<KeyError, Delivery> {
            val deadline = held.deadline
                ?: return held.material.borrow(clock, ::register).map { Delivery.View(it) }
            if (held.revoked.peek()) return Either.Left(viewDenied)
            val answer = if (clock.now() >= deadline) Either.Left(viewExpired)
            else held.material.borrow(clock, ::register)
            if (held.revoked.peek()) return Either.Left(viewDenied)
            if (answer is Either.Left && answer.value is KeyError.Unavailable) ledger.expire(ref)
            return answer.map { Delivery.View(ledger.guarded(ref, held, it)) }
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
                val call = DeliveryCall(ledger, receipt, load) { it }
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
            ): Either<KeyError, Delivery> = callers.deliver(DeliveryCall(ledger, receipt, load) { it })

            fun copy(
                callers: KeyCallers, ledger: KeyCacheLedger, receipt: Resource<Receipt>,
                load: () -> Either<KeyError, DataKey>,
            ): Either<KeyError, DataKey> {
                val call = DeliveryCall(ledger, receipt, load) { (it as Delivery.Copy).key }
                return callers.deliver(call)
            }

            fun view(
                callers: KeyCallers, ledger: KeyCacheLedger, receipt: Resource<Receipt>,
                load: () -> Either<KeyError, DataKey>,
            ): Either<KeyError, KeyView> =
                callers.deliver(
                    DeliveryCall(ledger, receipt, load, { Either.Left(viewDenied) }) { (it as Delivery.View).view },
                )
        }
    }

    private fun awaitSource(
        receipt: Receipt, flights: SingleFlight<Any, KeyError, Unit>, load: () -> Either<KeyError, DataKey>,
    ): Either<KeyError, SourceRef> {
        val group = state.peek().groups.getValue(receipt.group)
        val frozen = group.outcome
        val answer = if (frozen !=
            null
        ) replay(frozen) else flights.run(receipt.group) { settle(receipt, load) }.flatMap {
            replay(checkNotNull(state.peek().groups[receipt.group]?.outcome))
        }
        val settled = state.peek().groups.getValue(receipt.group)
        if (settled.revoked) return Either.Left(viewDenied)
        val stale = group.captured ?: return answer
        if (answer !is Either.Left || answer.value !is KeyError.Unavailable) return answer
        if ((settled.outcome as? Value)?.coldExpired == true) return answer
        if (receipt.clock.now() >= stale.loadedAt + hard) {
            state.update { it.drop(stale) }
            return answer
        }
        val current = state.peek()
        return if (current.retention.contains(stale)) Either.Right(current.records.getValue(stale.sourceId)) else answer
    }

    private fun guarded(ref: SourceRef, held: Qualified, view: KeyView): KeyView = object : KeyView by view {
        override fun <A> using(block: (ByteArray) -> A): Either<KeyError, A> {
            if (callers != null) return callers.using { useHeld(block) }
            checkNotInTransaction()
            return useHeld(block)
        }

        private fun <A> useHeld(block: (ByteArray) -> A): Either<KeyError, A> {
            if (held.revoked.peek()) return Either.Left(viewDenied)
            val delegated = view.using { bytes ->
                if (held.revoked.peek()) return@using Either.Left(viewDenied)
                val value = block(bytes)
                if (held.revoked.peek()) Either.Left(viewDenied) else Either.Right(value)
            }
            if (held.revoked.peek()) return Either.Left(viewDenied)
            val answer = delegated.flatMap { it }
            if (answer is Either.Left && answer.value == viewExpired) expire(ref)
            return answer
        }

        override fun close() {
            checkNotInTransaction()
            view.close()
        }
    }

    private fun expire(ref: SourceRef) = resourceScope {
        onRelease { _ -> drain() }
        state.update { it.dropSource(ref.slot, ref.id) }
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

    fun enrol(key: LoadKey, clock: Clock, deadline: Instant): Resource<Receipt> =
        enrol(key, clock, deadline, retained = false)

    fun enrol(slot: KeyCache.Slot, clock: Clock): Resource<Receipt> =
        enrol(LoadKey(slot, null, Any()), clock, Instant.MAX, retained = true)

    fun enrol(identity: KeyIdentity, clock: Clock): Resource<Receipt> {
        val slot = KeyCache.Slot(identity.owner, identity.purpose, identity.version)
        return enrol(LoadKey(slot, identity, Any()), clock, Instant.MAX, retained = true)
    }

    private fun enrol(key: LoadKey, clock: Clock, deadline: Instant, retained: Boolean): Resource<Receipt> = resource {
        val id = Any()
        val candidate = Any()
        if (state.peek().closed) return@resource Receipt(candidate, id, clock, closed = true)
        val inherited = active.get()
        val now = if (retained) clock.now() else deadline
        onRelease { _ -> finishReceipt(id) }
        val group = state.modify { current ->
            if (current.closed) current to null
            else current.enrolReceipt(key, deadline, retained, now, id, candidate, inherited)
        }
        Receipt(group ?: candidate, id, clock, closed = group == null)
    }

    private fun State.enrolReceipt(
        key: LoadKey,
        deadline: Instant,
        retained: Boolean,
        now: Instant,
        id: Any,
        candidate: Any,
        inherited: Set<Any>,
    ): Pair<State, Any> {
        val admitted = if (retained) admit(key) else null
        val admission = admitted?.admission
        val loadKey = if (admission == null) key else key.copy(epoch = admission.epoch)
        val fresh = admission?.captured?.let { hit(it, now) }
        val token = if (fresh == null) open[loadKey] ?: candidate else candidate
        check(token !in inherited) { "recursive key load" }
        val held = groups[token] ?: Group(loadKey, deadline, outcome = fresh, admission = admission)
        val enrolled = copy(
            open = if (fresh == null) open + (loadKey to token) else open,
            receipts = receipts + (id to token),
            groups = groups + (token to held.copy(members = held.members + 1)),
        )
        return enrolled.retain(admitted?.policy ?: retention, admitted?.displaced.orEmpty()) to token
    }

    private fun State.admit(key: LoadKey): KeyRetention.Admitted =
        key.identity?.let { retention.admit(it, key.epoch) } ?: retention.admit(key.slot, key.epoch)

    private fun State.hit(record: KeyRetention.Record, now: Instant): Value? =
        if (now < record.loadedAt + soft) Value(Either.Right(records.getValue(record.sourceId))) else null

    fun evict(owner: KeyOwner) = resourceScope {
        checkNotInTransaction()
        onRelease { _ -> drain() }
        state.update { current -> current.retention.forget(owner).let { current.retain(it.policy, it.displaced) } }
    }

    fun revoke(owner: KeyOwner?) = resourceScope {
        checkNotInTransaction()
        onRelease { _ -> drain() }
        publishRevocation(owner)
    }

    fun fenceForClose() = publishRevocation(null, closing = true)

    fun closed(): Boolean = state.peek().closed

    fun hasObligations(): Boolean {
        val current = state.peek()
        val live = current.groups.isNotEmpty() || current.receipts.isNotEmpty() || current.open.isNotEmpty()
        val held = current.records.isNotEmpty() || current.prepared.isNotEmpty() || current.pending.isNotEmpty()
        return live || held
    }

    private fun publishRevocation(owner: KeyOwner?, closing: Boolean = false) {
        state.update { current ->
            if (closing && current.closed) return@update current
            val affected = current.records.values.filter { owner == null || it.slot.owner == owner }
            val pending = affected.mapNotNull { it.source as? Qualified }
                .filter { it.revoked.modify { revoked -> true to !revoked } }
                .map { it.revoke }
            val groups = current.groups.mapValues { (_, group) ->
                val identity = group.key.identity
                val affectedGroup = identity != null && (owner == null || identity.owner == owner)
                if (affectedGroup) group.copy(revoked = true) else group
            }
            val removed = if (owner == null) current.retention.forgetAll() else current.retention.forget(owner)
            current.copy(closed = closing || current.closed, groups = groups, pending = current.pending + pending)
                .retain(removed.policy, removed.displaced)
        }
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
                    val loadedAt = if (group.admission == null) group.deadline else receipt.clock.now()
                    val loaded = load()
                    if (loaded is Either.Right) transferred = loaded.value
                    val result = loaded.flatMap { takeSource(group, id, it, loadedAt, receipt.clock) }
                    state.modify { current ->
                        val held = checkNotNull(current.groups[receipt.group])
                        val frozen = if (held.revoked) Either.Left(viewDenied) else result
                        val source = frozen.getOrNull()
                        val expired = loaded is Either.Right && (frozen as? Either.Left)?.value === viewExpired
                        current.copy(
                            open = current.open.filterValues { it !== receipt.group },
                            groups = current.groups + (receipt.group to held.copy(outcome = Value(frozen, expired))),
                            records = if (source == null) current.records else current.records + (id to source),
                        ).publish(held, frozen, loadedAt) to frozen
                    }.map { Unit }
                } catch (failure: Throwable) {
                    failed = failure
                    throw failure
                }
            }
        }

    private fun takeSource(
        group: Group, id: Any, key: DataKey, at: Instant, clock: Clock,
    ): Either<KeyError, SourceRef> {
        val slot = group.key.slot
        val identity = group.key.identity ?: return Either.Right(SourceRef(id, slot, Legacy(key)))
        val deadline = group.admission?.let { at + hard }
        return OwnedKeyMaterial.take(identity, key, deadline ?: group.deadline) {
            state.update { it.copy(records = it.records - id).dropSource(slot, id) }
        }.flatMap { material ->
            if (deadline != null && clock.now() >= deadline) Either.Left(viewExpired)
            else Either.Right(SourceRef(id, slot, Qualified(material, deadline)))
        }
    }

    private fun State.dropSource(slot: KeyCache.Slot, id: Any): State =
        retention.retained(slot, id)?.let { drop(it) } ?: this

    private fun State.publish(group: Group, result: Either<KeyError, SourceRef>, at: Instant): State {
        val admission = group.admission ?: return this
        return when (result) {
            is Either.Right -> retention.publish(admission, result.value.id, at)
                .let { retain(it.policy, listOfNotNull(it.displaced)) }

            is Either.Left -> group.captured?.takeUnless { result.value is KeyError.Unavailable }
                ?.let { drop(it) } ?: this
        }
    }

    private fun State.drop(record: KeyRetention.Record): State =
        retention.drop(record).let { retain(it.policy, it.displaced) }

    private fun State.retain(next: KeyRetention, displaced: List<KeyRetention.Record>): State =
        copy(retention = next).retire(displaced.map { it.sourceId })

    private fun State.retire(ids: List<Any>): State {
        if (ids.isEmpty()) return this
        val released = ids.distinct().mapNotNull(records::get).filter { source ->
            retention.retained(source.slot, source.id) == null && groups.values.none {
                it.source === source || it.captured?.sourceId === source.id
            }
        }
        val legacy = released.filter { it.source is Legacy }.map { it.id }
        return copy(records = records - legacy, pending = pending + released.map { it.source.release })
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
        if (group.admission != null || group.source == null) {
            return current.copy(open = current.open.filterValues { it !== id }, groups = current.groups - id)
                .retire(listOfNotNull(group.captured?.sourceId, group.source?.id))
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

    private enum class CloseStage { FENCE, REVOKE, STOP, OBSERVE, REAP }

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

        fun phase(stage: CloseStage, ledger: KeyCacheLedger?) {
            runPhase({
                when (stage) {
                    CloseStage.FENCE -> fence()
                    CloseStage.REVOKE -> checkNotNull(ledger).fenceForClose()
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

                    CloseStage.REVOKE -> checkNotNull(ledger).closed()

                    CloseStage.REAP -> state.peek().tickets.none { ticket -> terminal.any { it === ticket } }

                    CloseStage.STOP, CloseStage.OBSERVE -> false
                }
            })
        }

        fun drain(ledger: KeyCacheLedger, public: Boolean) {
            if (!public) return ledger.drainAfterClose(::runPhase)
            resourceScope {
                onRelease { _ ->
                    if (ledger.hasObligations()) {
                        outcome.record(IllegalStateException("the key cache cleanup is incomplete"))
                    }
                }
                onThrow(outcome::record) { ledger.drainAfterClose(::runPhase) }
            }
        }

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

    fun closeAndAwait(ledger: KeyCacheLedger?) = closeAndAwait(ledger, public = false)

    fun closePublic(ledger: KeyCacheLedger) = closeAndAwait(ledger, public = true)

    // Capture inside the owner binding before restoration can mask it; outer capture retains restoration too.
    @Suppress("TooGenericExceptionCaught")
    private fun closeAndAwait(ledger: KeyCacheLedger?, public: Boolean) {
        checkNotInTransaction()
        check(identity !in active.get()) { "a key cache caller cannot close its owner" }
        val closing = Closing()
        var boundaryFailure: Throwable? = null
        try {
            try {
                active.locally(active.get() + identity) {
                    try {
                        CloseStage.entries.forEach { stage ->
                            if (public || stage != CloseStage.REVOKE) closing.phase(stage, ledger)
                        }
                        ledger?.let { closing.drain(it, public) }
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

        fun <A> run(body: () -> A, refuse: () -> A = ::closedKeyCache): A = withinOwner {
            if (admit(ticket, invocation)) body()
            else {
                invocation.assembled()?.let { throw it }
                refuse()
            }
        }

        fun abort(call: KeyCacheLedger.DeliveryCall<*>) {
            if (call.chooseAbort()) withinOwner(call::abort)
        }

        // The failed cleanup must carry the caller's original failure, with distinct diagnostics.
        @Suppress("ThrowingExceptionFromFinally")
        fun abortAfterFailure(call: KeyCacheLedger.DeliveryCall<*>, primary: Throwable) {
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

    fun <A> using(body: () -> Either<KeyError, A>): Either<KeyError, A> {
        checkNotInTransaction()
        val accepted = AcceptedCall()
        try {
            return accepted.run(body) { Either.Left(viewDenied) }
        } finally {
            accepted.ticket.finished.tryComplete(Unit)
        }
    }

    fun <A> deliver(call: KeyCacheLedger.DeliveryCall<A>): Either<KeyError, A> {
        checkNotInTransaction()
        val accepted = AcceptedCall()
        try {
            val answer = onThrow({ primary -> accepted.abortAfterFailure(call, primary) }) {
                accepted.run({ call.prepare(flights) }, call.refuse)
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
        return invocation.admitted
    }
}
