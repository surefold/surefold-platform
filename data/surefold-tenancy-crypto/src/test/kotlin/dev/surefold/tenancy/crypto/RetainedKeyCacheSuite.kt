package dev.surefold.tenancy.crypto

import dev.surefold.Counter
import dev.surefold.Deferred
import dev.surefold.ExitCase
import dev.surefold.HandoffDecision
import dev.surefold.Metrics
import dev.surefold.NoMetrics
import dev.surefold.Promise
import dev.surefold.ResourceScope
import dev.surefold.Shared
import dev.surefold.SingleFlight
import dev.surefold.SystemClock
import dev.surefold.TestClock
import dev.surefold.TurnSeam
import dev.surefold.Turns
import dev.surefold.awaitValue
import dev.surefold.clock
import dev.surefold.fail.Either
import dev.surefold.fail.attempt
import dev.surefold.fail.flatMap
import dev.surefold.fixedClock
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeLeft
import dev.surefold.junit.shouldThrow
import dev.surefold.metrics
import dev.surefold.resource
import dev.surefold.resourceScope
import dev.surefold.scope
import dev.surefold.takingTurns
import dev.surefold.tenancy.TenantId
import dev.surefold.test.eventually
import dev.surefold.timeout
import dev.surefold.transact
import dev.surefold.use
import java.time.Instant
import java.util.concurrent.CancellationException
import java.util.concurrent.locks.LockSupport
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

object RetainedLeaseSuite : Suite() {
    private fun identity(revision: Int = 1, owner: KeyOwner = KeyOwner.Platform) =
        KeyIdentity(owner, KeyPurpose.Enc, 1, byteArrayOf(revision.toByte()))

    private fun loaded(identity: KeyIdentity, source: ByteArray) =
        Either.Right(DataKey(identity.owner, identity.purpose, identity.version, source))

    private fun ResourceScope.borrow(cache: KeyCache, identity: KeyIdentity, vararg material: Byte): KeyView {
        val view = (cache.lease(identity) { loaded(identity, material) } as Either.Right).value
        onRelease { _ -> view.close() }
        return view
    }

    private fun read(view: KeyView) = view.using { it.single().toInt() }

    init {
        test("forget keeps a held lease valid and makes the next lease load fresh material") {
            val cache = KeyCache()
            val key = identity()
            resourceScope {
                onRelease { _ -> cache.revokeAll() }
                val held = borrow(cache, key, 3)
                cache.evict(key.owner)
                read(held) shouldBe Either.Right(3)
                val fresh = borrow(cache, key, 7)
                val unrelated = borrow(cache, identity(owner = KeyOwner.Tenant(TenantId("synthetic-unrelated"))), 9)

                read(fresh) shouldBe Either.Right(7)
                read(held) shouldBe Either.Right(3)
                cache.revoke(key.owner)
                read(held) shouldBe Either.Left(KeyError.Denied("the key view is unavailable"))
                read(fresh) shouldBe Either.Left(KeyError.Denied("the key view is unavailable"))
                read(unrelated) shouldBe Either.Right(9)
                cache.evict(unrelated.identity.owner)
                cache.revokeAll()
                read(unrelated) shouldBe Either.Left(KeyError.Denied("the key view is unavailable"))
            }
        }

        test("Unavailable in the same slot cannot reuse material from another identity") {
            val cache = KeyCache()
            val time = TestClock()
            val refusal = Either.Left(KeyError.Unavailable("synthetic qualified refusal"))
            clock.locally(time) {
                resourceScope {
                    onRelease { _ -> cache.revokeAll() }
                    borrow(cache, identity(), 3)
                    time.adjust(16.minutes)
                    val answer = cache.lease(identity(2)) { refusal }
                    if (answer is Either.Right) onRelease { _ -> answer.value.close() }

                    (answer === refusal) shouldBe true
                }
            }
        }

        test("a definitive refusal from a forgotten epoch cannot remove newer retention") {
            val cache = KeyCache()
            val key = identity()
            val time = TestClock()
            val entered = Promise<Nothing, Unit>()
            val resume = Promise<Nothing, Unit>()
            val refusal = Either.Left(KeyError.Denied("synthetic old refresh"))
            clock.locally(SystemClock) {
                timeout(10.seconds) {
                    clock.locally(time) {
                        resourceScope {
                            onRelease { _ -> cache.revokeAll() }
                            borrow(cache, key, 3)
                            time.adjust(16.minutes)
                            scope {
                                onClose { resume.succeed(Unit) }
                                val old = async {
                                    cache.lease(key) {
                                        entered.succeed(Unit)
                                        resume.awaitValue()
                                        refusal
                                    }
                                }
                                entered.awaitValue()
                                cache.evict(key.owner)
                                borrow(cache, key, 7)
                                resume.succeed(Unit)
                                (old.await() === refusal) shouldBe true

                                read(borrow(cache, key, 9)) shouldBe Either.Right(7)
                            }
                        }
                    }
                }
            }
        }
        test("a forgotten view keeps its bound clock and its terminal notice preserves the replacement") {
            val cache = KeyCache()
            val key = identity()
            val oldTime = TestClock()
            val newTime = TestClock()
            resourceScope {
                onRelease { _ -> cache.revokeAll() }
                val held = clock.locally(oldTime) { borrow(cache, key, 3) }
                cache.evict(key.owner)
                clock.locally(newTime) { borrow(cache, key, 7) }

                oldTime.adjust(60.minutes)
                clock.locally(newTime) {
                    read(held) shouldBe Either.Left(KeyError.Unavailable("the key view has expired"))
                    held.close()
                    read(borrow(cache, key, 9)) shouldBe Either.Right(7)
                }
            }
        }

        test("cold expiry wipes and its returned refusal can be forwarded by a provider") {
            val cache = KeyCache()
            val key = identity()
            val time = TestClock()
            val source = byteArrayOf(7)
            clock.locally(time) {
                resourceScope {
                    onRelease { _ -> cache.revokeAll() }
                    val result = cache.lease(key) {
                        time.adjust(60.minutes)
                        loaded(key, source)
                    }
                    if (result is Either.Right) onRelease { _ -> result.value.close() }
                    result shouldBe Either.Left(KeyError.Unavailable("the key view has expired"))
                    source.toList() shouldBe listOf<Byte>(0)

                    borrow(cache, key, 3)
                    time.adjust(16.minutes)
                    val fallback = cache.lease(key) { result as Either.Left }
                    if (fallback is Either.Right) onRelease { _ -> fallback.value.close() }
                    fallback.flatMap(::read) shouldBe Either.Right(3)
                }
            }
        }

        for (right in listOf(false, true)) {
            val outcome = if (right) "material" else "a refusal"
            test("an enrolled qualified load returning $outcome cannot answer across revoke") {
                val cache = KeyCache()
                val key = identity()
                val entered = Promise<Nothing, Unit>()
                val finishLoad = Promise<Nothing, Unit>()
                val source = byteArrayOf(7)
                clock.locally(SystemClock) {
                    timeout(10.seconds) {
                        resourceScope {
                            onRelease { _ -> cache.revokeAll() }
                            scope {
                                onClose { finishLoad.succeed(Unit) }
                                val pending = async {
                                    cache.lease(key) {
                                        entered.succeed(Unit)
                                        finishLoad.awaitValue()
                                        if (right) loaded(key, source)
                                        else Either.Left(KeyError.Unavailable("synthetic refusal"))
                                    }
                                }
                                entered.awaitValue()
                                cache.evict(key.owner)
                                cache.revokeAll()
                                finishLoad.succeed(Unit)
                                val result = pending.await()
                                if (result is Either.Right) onRelease { _ -> result.value.close() }

                                result shouldBe Either.Left(KeyError.Denied("the key view is unavailable"))
                                if (right) source.toList() shouldBe listOf<Byte>(0)
                                read(borrow(cache, key, 3)) shouldBe Either.Right(3)
                            }
                        }
                    }
                }
            }
        }

        test("committed revocation outranks hard expiry while a normal callback finishes") {
            val cache = KeyCache()
            val key = identity()
            val time = TestClock()
            val work = Promise<Nothing, ByteArray>()
            val finishUse = Promise<Nothing, Unit>()
            val committed = Promise<Nothing, Unit>()
            val releaseRevoke = Promise<Nothing, Unit>()
            clock.locally(SystemClock) {
                timeout(10.seconds) {
                    clock.locally(time) {
                        resourceScope {
                            onRelease { _ -> cache.revokeAll() }
                            val held = borrow(cache, key, 7)
                            scope {
                                onClose {
                                    finishUse.succeed(Unit)
                                    releaseRevoke.succeed(Unit)
                                }
                                val use = async {
                                    held.using { bytes ->
                                        work.succeed(bytes)
                                        finishUse.awaitValue()
                                    }
                                }
                                val working = work.awaitValue()
                                val revoke = async {
                                    atDeliveryCommit({ stack -> stack.any { it.methodName == "revoke" } }, {
                                        committed.succeed(Unit)
                                        releaseRevoke.awaitValue()
                                    }) { cache.revoke(key.owner) }
                                }
                                committed.awaitValue()
                                time.adjust(60.minutes)
                                finishUse.succeed(Unit)
                                use.await() shouldBe Either.Left(KeyError.Denied("the key view is unavailable"))
                                working.toList() shouldBe listOf<Byte>(0)
                                releaseRevoke.succeed(Unit)
                                revoke.await()
                            }
                        }
                    }
                }
            }
        }

        test("public view and revocation effects refuse ambient transactions") {
            val cache = KeyCache()
            val key = identity()
            resourceScope {
                onRelease { _ -> cache.revokeAll() }
                val held = borrow(cache, key, 7)
                shouldThrow<IllegalStateException>({ transact { held.using { true } } })
                shouldThrow<IllegalStateException>({ transact { held.close() } })
                shouldThrow<IllegalStateException>({ transact { cache.revoke(key.owner) } })
                shouldThrow<IllegalStateException>({ transact { cache.revokeAll() } })
                read(held) shouldBe Either.Right(7)
            }
        }
    }
}

object FrozenKeyDeliverySuite : Suite() {
    init {
        test("an already frozen enrolled receipt replays without starting another worker") {
            val source = byteArrayOf(4, 8)
            withLoadGroups(listOf(source)) { ledger, flights, key, captured ->
                val armed = HandoffDecision<Unit>()
                val settledByWorker = HandoffDecision<Unit>()
                val loads = Shared(0)
                val caller = Thread.currentThread()
                val held = resource {
                    val receipt = ledger.enrol(key, captured, groupDeadline).bind()
                    runGroup(ledger, flights, key, captured) {
                        loads.update { it + 1 }
                        Either.Right(DataKey(key.slot.owner, key.slot.purpose, 1, source))
                    }
                    armed.tryComplete(Unit)
                    receipt
                }
                val previous = metrics.get()
                val observer = object : Metrics by previous {
                    override fun counter(name: String, tags: Map<String, String>): Counter = Counter { count ->
                        previous.counter(name, tags).increment(count)
                        val thread = Thread.currentThread()
                        val commit = name == "surefold.transact.runs" && tags["outcome"] == "committed"
                        val recording = commit && thread !== caller && armed.peek() == Unit
                        if (recording) {
                            val settling = thread.stackTrace.any { frame ->
                                frame.className == KeyCacheLedger::class.java.name &&
                                    frame.methodName.substringBefore('$') == "settle"
                            }
                            if (settling) settledByWorker.tryComplete(Unit)
                        }
                    }
                }
                resourceScope {
                    val result = metrics.locally(observer) {
                        KeyCacheLedger.DeliveryCall.run(KeyCallers(flights), ledger, held) {
                            error("a frozen receipt must replay the original value")
                        }
                    }
                    val delivery = (result as Either.Right).value
                    onRelease { _ -> discardDelivery(delivery) }
                    deliveryBytes(delivery) shouldBe listOf<Byte>(4, 8)
                    loads.peek() shouldBe 1
                    settledByWorker.peek() shouldBe null
                }
            }
        }

        test("a typed copy survives source cleanup and a later prepared-row reap") {
            val source = byteArrayOf(3, 9)
            withLoadGroups(listOf(source)) { ledger, flights, key, captured ->
                resourceScope {
                    val receipt = ledger.enrol(key, captured, groupDeadline)
                    val result = KeyCacheLedger.DeliveryCall.copy(KeyCallers(flights), ledger, receipt) {
                        Either.Right(DataKey(key.slot.owner, key.slot.purpose, 1, source))
                    }
                    val copy = (result as Either.Right).value
                    onRelease { _ -> copy.material.fill(0) }
                    ledger.drain()

                    source.toList() shouldBe listOf<Byte>(0, 0)
                    copy.material.toList() shouldBe listOf<Byte>(3, 9)
                    (copy.material === source) shouldBe false
                }
            }
        }
    }
}

object KeyCacheLegacyRetentionSuite : Suite() {
    private val slot = KeyCache.Slot(KeyOwner.Platform, KeyPurpose.Enc, null)

    private fun loaded(source: ByteArray) = Either.Right(DataKey(slot.owner, slot.purpose, 1, source))

    init {
        test("a pre-enrolled caller rechecks the hard cutoff after another caller used the fallback") {
            val time = TestClock()
            val cache = KeyCache()
            val source = byteArrayOf(7)
            val refusal = Either.Left(KeyError.Unavailable("synthetic refresh refusal"))
            val entered = Promise<Nothing, Unit>()
            val finishLoad = Promise<Nothing, Unit>()
            val enrolled = Promise<Nothing, Unit>()
            val finishWait = Promise<Nothing, Unit>()

            clock.locally(SystemClock) {
                timeout(10.seconds) {
                    clock.locally(time) {
                        cache.get(slot) { loaded(source) }
                        time.adjust(55.minutes)
                        scope {
                            onClose {
                                finishLoad.succeed(Unit)
                                finishWait.succeed(Unit)
                            }
                            val first = async {
                                cache.get(slot) {
                                    entered.succeed(Unit)
                                    finishLoad.awaitValue()
                                    refusal
                                }
                            }
                            entered.awaitValue()
                            val second = async {
                                atDeliveryCommit({ stack ->
                                    stack.any { frame ->
                                        frame.className == KeyCacheLedger::class.java.name &&
                                            frame.methodName.substringBefore('$') == "enrol"
                                    }
                                }, {
                                    enrolled.succeed(Unit)
                                    finishWait.awaitValue()
                                }) {
                                    cache.get(slot) { error("the pre-enrolled caller must replay the result") }
                                }
                            }
                            enrolled.awaitValue()
                            finishLoad.succeed(Unit)
                            val fallback = (first.await() as Either.Right).value
                            fallback.material.toList() shouldBe listOf<Byte>(7)
                            time.adjust(6.minutes)
                            finishWait.succeed(Unit)
                            (second.await() === refusal) shouldBe true
                            source.toList() shouldBe listOf<Byte>(0)
                            fallback.material.toList() shouldBe listOf<Byte>(7)
                        }
                    }
                }
            }
        }

        for (outcome in listOf("refuses", "cancels")) {
            test("forget leaves a stale source owned through a refresh that $outcome") {
                val time = TestClock()
                val cache = KeyCache()
                val source = byteArrayOf(9)
                val refusal = Either.Left(KeyError.Unavailable("synthetic refresh refusal"))
                val cancellation = CancellationException("synthetic refresh cancellation")
                val entered = Promise<Nothing, Unit>()
                val finishLoad = Promise<Nothing, Unit>()

                clock.locally(SystemClock) {
                    timeout(10.seconds) {
                        clock.locally(time) {
                            val copy = (cache.get(slot) { loaded(source) } as Either.Right).value
                            time.adjust(55.minutes)
                            scope {
                                onClose { finishLoad.succeed(Unit) }
                                val refresh = async {
                                    val load = {
                                        cache.get(slot) {
                                            entered.succeed(Unit)
                                            finishLoad.awaitValue()
                                            if (outcome == "cancels") throw cancellation
                                            refusal
                                        }
                                    }
                                    if (outcome == "cancels") {
                                        shouldThrow<CancellationException>({ load() }) {
                                            (it === cancellation) shouldBe true
                                        }
                                    } else {
                                        (load() === refusal) shouldBe true
                                    }
                                }
                                entered.awaitValue()
                                cache.evict(slot.owner)
                                source.toList() shouldBe listOf<Byte>(9)
                                finishLoad.succeed(Unit)
                                refresh.await()
                                source.toList() shouldBe listOf<Byte>(0)
                                copy.material.toList() shouldBe listOf<Byte>(9)
                            }
                        }
                    }
                }
            }
        }

        test("a late load from the forgotten epoch answers its copy without replacing the fresh epoch") {
            val cache = KeyCache()
            val old = byteArrayOf(3)
            val new = byteArrayOf(7)
            val entered = Promise<Nothing, Unit>()
            val finishLoad = Promise<Nothing, Unit>()

            clock.locally(SystemClock) {
                timeout(10.seconds) {
                    scope {
                        onClose { finishLoad.succeed(Unit) }
                        val pending = async {
                            cache.get(slot) {
                                entered.succeed(Unit)
                                finishLoad.awaitValue()
                                loaded(old)
                            }
                        }
                        entered.awaitValue()
                        cache.evict(slot.owner)
                        val fresh = (cache.get(slot) { loaded(new) } as Either.Right).value
                        finishLoad.succeed(Unit)
                        val late = (pending.await() as Either.Right).value
                        val hit = cache.get(slot) { error("the fresh source must remain cached") }
                        val retained = (hit as Either.Right).value

                        old.toList() shouldBe listOf<Byte>(0)
                        late.material.toList() shouldBe listOf<Byte>(3)
                        fresh.material.toList() shouldBe listOf<Byte>(7)
                        retained.material.toList() shouldBe listOf<Byte>(7)
                        (fresh.material === retained.material) shouldBe false
                    }
                }
            }
        }
    }
}

object KeyRetentionSuite : Suite() {
    init {
        test("forgetAll fences a legacy-only epoch with a pending admission and no retained source") {
            val slot = KeyCache.Slot(KeyOwner.Platform, KeyPurpose.Enc, null)
            val pending = KeyRetention(1).admit(slot, Any())
            val forgotten = pending.policy.forgetAll()
            val new = forgotten.policy.admit(slot, Any())

            forgotten.displaced shouldBe emptyList()
            new.policy.publish(pending.admission, Any(), Instant.EPOCH).accepted shouldBe false
        }

        test("a slower successful load cannot replace a newer success or restore it after removal") {
            val slot = KeyCache.Slot(KeyOwner.Platform, KeyPurpose.Enc, 1)
            val old = KeyRetention(1).admit(slot, Any())
            val newer = old.policy.admit(slot, Any())
            val recentSource = Any()
            val at = Instant.EPOCH.plusSeconds(10)
            val recent = newer.policy.publish(newer.admission, recentSource, at)
            val later = recent.policy.publish(old.admission, Any(), Instant.EPOCH)

            val held = later.policy.admit(slot, Any()).admission.captured
            (held?.sourceId === recentSource) shouldBe true
            later.accepted shouldBe false
            later.displaced shouldBe null
            val dropped = later.policy.drop(checkNotNull(held))
            dropped.displaced shouldBe listOf(held)
            val stale = dropped.policy.publish(old.admission, Any(), Instant.EPOCH)
            stale.accepted shouldBe false
            stale.policy.admit(slot, Any()).admission.captured shouldBe null
        }

        test("a newer admission without a success does not prevent an older success") {
            val slot = KeyCache.Slot(KeyOwner.Platform, KeyPurpose.Enc, 1)
            val first = KeyRetention(1).admit(slot, Any())
            val failed = first.policy.admit(slot, Any())
            val source = Any()
            val loadedAt = Instant.EPOCH.plusSeconds(5)

            val published = failed.policy.publish(first.admission, source, loadedAt)

            published.accepted shouldBe true
            val fresh = published.policy.admit(slot, Any())
            val again = fresh.policy.admit(slot, Any())
            val held = checkNotNull(again.admission.captured)
            (held.sourceId === source) shouldBe true
            held.ticket shouldBe first.admission.ticket
            held.loadedAt shouldBe loadedAt
        }

        test("forget fences a late publication after the same owner returns") {
            val slot = KeyCache.Slot(KeyOwner.Platform, KeyPurpose.Enc, null)
            val first = KeyRetention(1).admit(slot, Any())
            val published = first.policy.publish(first.admission, Any(), Instant.EPOCH)
            val pending = published.policy.admit(slot, Any())
            val held = checkNotNull(pending.admission.captured)
            val forgotten = pending.policy.forget(slot.owner)
            val newEpoch = Any()
            val returned = forgotten.policy.admit(slot, newEpoch)

            forgotten.displaced shouldBe listOf(held)
            (returned.admission.epoch === newEpoch) shouldBe true
            (returned.admission.epoch === pending.admission.epoch) shouldBe false
            returned.admission.captured shouldBe null
            val late = returned.policy.publish(pending.admission, Any(), Instant.EPOCH)
            late.accepted shouldBe false
            (late.policy === returned.policy) shouldBe true
        }

        test("legacy and qualified admissions share owner recency and capacity") {
            val active = KeyCache.Slot(KeyOwner.Platform, KeyPurpose.Enc, null)
            val tenant = KeyOwner.Tenant(TenantId("synthetic-retention"))
            val qualified = KeyIdentity(tenant, KeyPurpose.Enc, 1, byteArrayOf(3))
            val first = KeyRetention(2).admit(active, Any())
            val legacy = first.policy.publish(first.admission, Any(), Instant.EPOCH)
            val second = legacy.policy.admit(qualified, Any())
            val qualifiedSource = Any()
            val current = second.policy.publish(second.admission, qualifiedSource, Instant.EPOCH)
            val touch = current.policy.admit(KeyIdentity(active.owner, active.purpose, 1, byteArrayOf(4)), Any())
            val third = KeyCache.Slot(KeyOwner.Tenant(TenantId("synthetic-other")), KeyPurpose.Enc, null)

            val overflow = touch.policy.admit(third, Any())

            overflow.displaced.size shouldBe 1
            (overflow.displaced.single().sourceId === qualifiedSource) shouldBe true
            (touch.admission.epoch === first.admission.epoch) shouldBe true
            touch.admission.captured shouldBe null
            overflow.policy.contains(overflow.displaced.single()) shouldBe false
            val hit = overflow.policy.admit(active, Any())
            (hit.admission.epoch === first.admission.epoch) shouldBe true
            (hit.admission.captured != null) shouldBe true
            overflow.policy.publish(second.admission, Any(), Instant.EPOCH).accepted shouldBe false
        }

        test("revisions and modes compete in one versioned slot without replacing the active alias") {
            val owner = KeyOwner.Platform
            val alias = KeyCache.Slot(owner, KeyPurpose.Enc, null)
            val legacy = alias.copy(version = 1)
            val firstIdentity = KeyIdentity(owner, KeyPurpose.Enc, 1, byteArrayOf(1))
            val sameIdentity = KeyIdentity(owner, KeyPurpose.Enc, 1, byteArrayOf(1))
            val revised = KeyIdentity(owner, KeyPurpose.Enc, 1, byteArrayOf(2))
            val first = KeyRetention(1).admit(alias, Any())
            val activeSource = Any()
            val active = first.policy.publish(first.admission, activeSource, Instant.EPOCH)
            val admission = active.policy.admit(firstIdentity, Any())
            val initial = admission.policy.publish(admission.admission, Any(), Instant.EPOCH)
            val equal = initial.policy.admit(sameIdentity, Any())
            val distinct = equal.policy.admit(revised, Any())

            val old = checkNotNull(equal.admission.captured)
            distinct.admission.captured shouldBe null
            distinct.policy.admit(legacy, Any()).admission.captured shouldBe null
            val replacement = distinct.policy.publish(distinct.admission, Any(), Instant.EPOCH.plusSeconds(1))
            replacement.displaced shouldBe old
            val retained = checkNotNull(replacement.policy.admit(revised, Any()).admission.captured)
            val unqualified = replacement.policy.admit(legacy, Any())
            val copied = unqualified.policy.publish(unqualified.admission, Any(), Instant.EPOCH)
            copied.displaced shouldBe retained
            copied.policy.admit(revised, Any()).admission.captured shouldBe null
            (copied.policy.admit(alias, Any()).admission.captured?.sourceId === activeSource) shouldBe true
        }

        test("dropping a captured record cannot remove its same-identity replacement") {
            val identity = KeyIdentity(KeyOwner.Platform, KeyPurpose.Enc, 1, byteArrayOf(1))
            val admitted = KeyRetention(1).admit(identity, Any())
            val first = admitted.policy.publish(admitted.admission, Any(), Instant.EPOCH)
            val refresh = first.policy.admit(identity, Any())
            val old = checkNotNull(refresh.admission.captured)
            val source = Any()
            val updated = refresh.policy.publish(refresh.admission, source, Instant.EPOCH.plusSeconds(1))
            val current = checkNotNull(updated.policy.admit(identity, Any()).admission.captured)

            val removal = updated.policy.drop(old)

            (current.sourceId === source) shouldBe true
            removal.displaced shouldBe emptyList()
            removal.policy.contains(old) shouldBe false
            removal.policy.contains(current) shouldBe true
        }
    }
}

object RetainedKeyCacheSuite : Suite() {
    init {
        test("an owed release waits for an explicit drain") {
            val ledger = KeyCacheLedger()
            val calls = Shared(0)

            ledger.owe { calls.update { it + 1 } }
            calls.peek() shouldBe 0
            ledger.drain()
            calls.peek() shouldBe 1
        }

        test("a successful release is acknowledged and never replayed") {
            val ledger = KeyCacheLedger()
            val calls = Shared(0)
            ledger.owe { calls.update { it + 1 } }

            ledger.drain()
            ledger.drain()

            calls.peek() shouldBe 1
        }

        test("an action throwing after its own commit stays pending") {
            val ledger = KeyCacheLedger()
            val attempts = Shared(0)
            val present = Shared(true)
            val removals = Shared(0)
            val marker = IllegalStateException("synthetic release")
            ledger.owe {
                if (present.modify { false to it }) removals.update { it + 1 }
                val number = attempts.modify { it + 1 to it + 1 }
                if (number == 1) throw marker
            }

            shouldThrow<IllegalStateException>({ ledger.drain() }) { (it === marker) shouldBe true }
            ledger.drain()
            ledger.drain()

            attempts.peek() shouldBe 2
            removals.peek() shouldBe 1
        }

        test("a committed acknowledgement survives instrumentation throwing") {
            val ledger = KeyCacheLedger()
            val calls = Shared(0)
            val marker = IllegalStateException("synthetic acknowledgement")
            ledger.owe { metrics.locally(NoMetrics) { calls.update { it + 1 } } }

            onLedgerCommit(0, { throw marker }) {
                shouldThrow<IllegalStateException>({ ledger.drain() }) { (it === marker) shouldBe true }
            }
            ledger.drain()

            calls.peek() shouldBe 1
        }

        test("a committed intent remains owned when publication instrumentation throws") {
            val ledger = KeyCacheLedger()
            val attempts = Shared(0)
            val primary = IllegalStateException("synthetic publication")
            val cleanup = IllegalStateException("synthetic release")

            onLedgerCommit(0, { throw primary }) {
                shouldThrow<IllegalStateException>({
                    resourceScope {
                        onRelease { _ -> ledger.drain() }
                        ledger.owe {
                            val number = metrics.locally(NoMetrics) { attempts.modify { it + 1 to it + 1 } }
                            if (number == 1) throw cleanup
                        }
                    }
                }) {
                    (it === primary) shouldBe true
                    it.suppressed.toList() shouldBe listOf(cleanup)
                }
            }
            ledger.drain()
            ledger.drain()

            attempts.peek() shouldBe 2
        }

        test("a drain attempts every snapshot action once in insertion order") {
            val ledger = KeyCacheLedger()
            val attempts = Shared(listOf(0, 0, 0))
            val seen = Shared<List<Int>>(emptyList())
            val failures = listOf(IllegalStateException("synthetic first"), IllegalStateException("synthetic second"))
            repeat(3) { index ->
                ledger.owe {
                    seen.update { it + index }
                    val number = attempts.modify { counts ->
                        val next = counts.mapIndexed { position, count -> if (position == index) count + 1 else count }
                        next to next[index]
                    }
                    if (index < failures.size && number == 1) throw failures[index]
                }
            }

            shouldThrow<IllegalStateException>({ ledger.drain() }) {
                (it === failures[0]) shouldBe true
                it.suppressed.toList() shouldBe listOf(failures[1])
            }
            seen.peek() shouldBe listOf(0, 1, 2)
            attempts.peek() shouldBe listOf(1, 1, 1)
            ledger.drain()
            ledger.drain()

            seen.peek() shouldBe listOf(0, 1, 2, 0, 1)
            attempts.peek() shouldBe listOf(2, 2, 1)
        }

        test("work queued during a drain waits for the next drain") {
            val ledger = KeyCacheLedger()
            val first = Shared(0)
            val later = Shared(0)
            ledger.owe {
                first.update { it + 1 }
                ledger.owe { later.update { it + 1 } }
            }

            ledger.drain()
            first.peek() shouldBe 1
            later.peek() shouldBe 0
            ledger.drain()
            ledger.drain()

            first.peek() shouldBe 1
            later.peek() shouldBe 1
        }

        test("another successful acknowledgement cannot be undone by a failed attempt") {
            val ledger = KeyCacheLedger()
            val attempts = Shared(0)
            val entered = Promise<Nothing, Unit>()
            val finish = Promise<Nothing, Unit>()
            val marker = IllegalStateException("synthetic held attempt")
            ledger.owe {
                val number = attempts.modify { it + 1 to it + 1 }
                if (number == 1) {
                    entered.succeed(Unit)
                    finish.awaitValue()
                    throw marker
                }
            }
            clock.locally(SystemClock) {
                timeout(10.seconds) {
                    scope {
                        val first = async {
                            shouldThrow<IllegalStateException>({ ledger.drain() }) { (it === marker) shouldBe true }
                        }
                        try {
                            entered.awaitValue()
                            ledger.drain()
                        } finally {
                            finish.succeed(Unit)
                        }
                        first.await()
                    }
                }
            }
            ledger.drain()

            attempts.peek() shouldBe 2
        }

        test("a stale acknowledgement cannot remove a new intent using the same action") {
            val ledger = KeyCacheLedger()
            val attempts = Shared(0)
            val entered = Promise<Nothing, Unit>()
            val finish = Promise<Nothing, Unit>()
            val action: () -> Unit = {
                val number = attempts.modify { it + 1 to it + 1 }
                if (number == 1) {
                    entered.succeed(Unit)
                    finish.awaitValue()
                }
            }
            ledger.owe(action)
            clock.locally(SystemClock) {
                timeout(10.seconds) {
                    scope {
                        val first = async { ledger.drain() }
                        try {
                            entered.awaitValue()
                            ledger.drain()
                            ledger.owe(action)
                        } finally {
                            finish.succeed(Unit)
                        }
                        first.await()
                    }
                }
            }
            attempts.peek() shouldBe 2
            ledger.drain()
            ledger.drain()

            attempts.peek() shouldBe 3
        }

        test("a legacy source remains owned until its final receipt leaves") { normalGroupSource(qualified = false) }

        test("a qualified source remains owned until its final receipt leaves") { normalGroupSource(qualified = true) }

        test("a delayed enrolled receipt replays and keeps the frozen source owned") {
            val source = byteArrayOf(3, 4, 5)
            withLoadGroups(listOf(source)) { ledger, flights, key, captured ->
                val loads = Shared(0)
                resourceScope {
                    val delayed = ledger.enrol(key, captured, groupDeadline).bind()
                    runGroup(ledger, flights, key, captured) {
                        loads.update { it + 1 }
                        Either.Right(DataKey(KeyOwner.Platform, KeyPurpose.Enc, 1, source))
                    } shouldBe Either.Right(Unit)
                    source.toList() shouldBe listOf<Byte>(3, 4, 5)
                    flights.run(delayed.group) {
                        ledger.settle(delayed) {
                            loads.update { it + 1 }
                            Either.Left(KeyError.Unavailable("synthetic duplicate load"))
                        }
                    } shouldBe Either.Right(Unit)
                    source.toList() shouldBe listOf<Byte>(3, 4, 5)
                    loads.peek() shouldBe 1
                }
                source.all { it == 0.toByte() } shouldBe true
            }
        }

        test("frozen refusal serves only old receipts while a new caller loads again") {
            val source = byteArrayOf(3, 4, 5)
            withLoadGroups(listOf(source)) { ledger, flights, key, clock ->
                val loads = Shared(0)
                val refusal = Either.Left(KeyError.Unavailable("synthetic refusal"))
                resourceScope {
                    val first = ledger.enrol(key, clock, groupDeadline).bind()
                    val delayed = ledger.enrol(key, clock, groupDeadline).bind()
                    flights.run(first.group) {
                        ledger.settle(first) { loads.update { it + 1 }; refusal }
                    } shouldBe refusal
                    flights.run(delayed.group) {
                        ledger.settle(delayed) { loads.update { it + 1 }; refusal }
                    } shouldBe refusal
                    loads.peek() shouldBe 1
                    runGroup(ledger, flights, key, clock) {
                        loads.update { it + 1 }
                        Either.Right(DataKey(KeyOwner.Platform, KeyPurpose.Enc, 1, source))
                    } shouldBe Either.Right(Unit)
                    loads.peek() shouldBe 2
                    source.all { it == 0.toByte() } shouldBe true
                }
            }
        }

        test("a delayed receipt replays the exact loader cancellation exception") {
            withLoadGroups { ledger, flights, key, clock ->
                val loads = Shared(0)
                val marker = CancellationException("synthetic loader cancellation")
                resourceScope {
                    val first = ledger.enrol(key, clock, groupDeadline).bind()
                    val delayed = ledger.enrol(key, clock, groupDeadline).bind()
                    listOf(first, delayed).forEach { receipt ->
                        shouldThrow<CancellationException>({
                            flights.run(receipt.group) {
                                ledger.settle(receipt) { loads.update { it + 1 }; throw marker }
                            }
                        }) { (it === marker) shouldBe true }
                    }
                    loads.peek() shouldBe 1
                }
            }
        }

        test("a committed enrolment fault before run leaves no reserved loader") {
            val source = byteArrayOf(3, 4, 5)
            withLoadGroups(listOf(source)) { ledger, flights, key, clock ->
                val entered = Shared(false)
                val marker = IllegalStateException("synthetic enrolment")
                onLedgerCommit(0, { throw marker }) {
                    shouldThrow<IllegalStateException>({
                        ledger.enrol(key, clock, groupDeadline).use {
                            entered.update { true }
                        }
                    }) { (it === marker) shouldBe true }
                }
                entered.peek() shouldBe false
                runGroup(ledger, flights, key, clock) {
                    Either.Right(DataKey(KeyOwner.Platform, KeyPurpose.Enc, 1, source))
                } shouldBe Either.Right(Unit)
                source.all { it == 0.toByte() } shouldBe true
            }
        }

        test("closed SingleFlight never leaves an unentered group owning a loader") {
            val source = byteArrayOf(3, 4, 5)
            withLoadGroups(listOf(source)) { ledger, flights, key, clock ->
                val closed = SingleFlight<Any, KeyError, Unit>()
                closed.closeAndJoin()
                val loads = Shared(0)
                shouldThrow<IllegalStateException>({
                    runGroup(ledger, closed, key, clock) {
                        loads.update { it + 1 }
                        Either.Left(KeyError.Unavailable("must not run"))
                    }
                }) { it.message shouldBe "SingleFlight is closed" }
                loads.peek() shouldBe 0
                runGroup(ledger, flights, key, clock) {
                    Either.Right(DataKey(KeyOwner.Platform, KeyPurpose.Enc, 1, source))
                } shouldBe Either.Right(Unit)
                source.all { it == 0.toByte() } shouldBe true
            }
        }

        test("a worker claim fault closes the old group before invoking the supplier") {
            withLoadGroups { ledger, flights, key, clock ->
                val loads = Shared(0)
                val marker = IllegalStateException("synthetic claim")
                resourceScope {
                    val first = ledger.enrol(key, clock, groupDeadline).bind()
                    val delayed = ledger.enrol(key, clock, groupDeadline).bind()
                    onLedgerCommit(0, { throw marker }) {
                        shouldThrow<IllegalStateException>({
                            flights.run(first.group) {
                                ledger.settle(first) {
                                    metrics.locally(NoMetrics) { loads.update { it + 1 } }
                                    Either.Left(KeyError.Unavailable("must not load"))
                                }
                            }
                        }) { (it === marker) shouldBe true }
                    }
                    shouldThrow<IllegalStateException>({
                        flights.run(delayed.group) {
                            ledger.settle(delayed) {
                                loads.update { it + 1 }
                                Either.Left(KeyError.Unavailable("must replay the claim fault"))
                            }
                        }
                    }) { (it === marker) shouldBe true }
                    loads.peek() shouldBe 0
                }
            }
        }

        test("committed Value remains owned when its publication metrics throws") {
            val source = byteArrayOf(3, 4, 5)
            withLoadGroups(listOf(source)) { ledger, flights, key, clock ->
                val returned = Shared(false)
                val loads = Shared(0)
                val marker = IllegalStateException("synthetic outcome publication")
                resourceScope {
                    val first = ledger.enrol(key, clock, groupDeadline).bind()
                    val delayed = ledger.enrol(key, clock, groupDeadline).bind()
                    failCommitAfter(returned, marker) {
                        shouldThrow<IllegalStateException>({
                            flights.run(first.group) {
                                ledger.settle(first) {
                                    metrics.locally(NoMetrics) {
                                        loads.update { it + 1 }
                                        returned.update { true }
                                    }
                                    Either.Right(DataKey(KeyOwner.Platform, KeyPurpose.Enc, 1, source))
                                }
                            }
                        }) { (it === marker) shouldBe true }
                    }
                    source.toList() shouldBe listOf<Byte>(3, 4, 5)
                    flights.run(delayed.group) {
                        ledger.settle(delayed) {
                            loads.update { it + 1 }
                            Either.Left(KeyError.Unavailable("must replay committed Value"))
                        }
                    } shouldBe Either.Right(Unit)
                    source.toList() shouldBe listOf<Byte>(3, 4, 5)
                    loads.peek() shouldBe 1
                }
                source.all { it == 0.toByte() } shouldBe true
            }
        }

        test("direct reentry refuses before waiting on its own flight") { recursiveGroup(indirect = false) }
        test("inherited indirect reentry refuses before waiting on the outer flight") {
            recursiveGroup(indirect = true)
        }

        test("a nested new epoch is a different group and may load") {
            val outer = byteArrayOf(3, 4, 5)
            val inner = byteArrayOf(6, 7, 8)
            withLoadGroups(listOf(outer, inner)) { ledger, flights, key, captured ->
                val nested = key.copy(epoch = Any())
                runGroup(ledger, flights, key, captured) {
                    runGroup(ledger, flights, nested, captured) {
                        Either.Right(DataKey(KeyOwner.Platform, KeyPurpose.Enc, 1, inner))
                    } shouldBe Either.Right(Unit)
                    inner.all { it == 0.toByte() } shouldBe true
                    Either.Right(DataKey(KeyOwner.Platform, KeyPurpose.Enc, 1, outer))
                } shouldBe Either.Right(Unit)
                outer.all { it == 0.toByte() } shouldBe true
            }
        }

        test("a claimed loader survives its last waiting caller and serves the next receipt") {
            val source = byteArrayOf(3, 4, 5)
            val duplicate = byteArrayOf(6, 7, 8)
            withLoadGroups(listOf(source, duplicate)) { ledger, flights, key, captured ->
                val loads = Shared(0)
                val entered = Promise<Nothing, Unit>()
                val release = Promise<Nothing, Unit>()
                val nextEnrolled = Promise<Nothing, Unit>()
                clock.locally(SystemClock) {
                    timeout(10.seconds) {
                        scope {
                            val first = async {
                                runGroup(ledger, flights, key, captured) {
                                    loads.update { it + 1 }
                                    entered.succeed(Unit)
                                    release.awaitValue()
                                    Either.Right(DataKey(KeyOwner.Platform, KeyPurpose.Enc, 1, source))
                                }
                            }
                            try {
                                entered.awaitValue()
                                first.cancel()
                                val next = async {
                                    ledger.enrol(key, captured, groupDeadline).use { receipt ->
                                        nextEnrolled.succeed(Unit)
                                        flights.run(receipt.group) {
                                            ledger.settle(receipt) {
                                                loads.update { it + 1 }
                                                Either.Right(DataKey(KeyOwner.Platform, KeyPurpose.Enc, 1, duplicate))
                                            }
                                        }
                                        source.toList()
                                    }
                                }
                                nextEnrolled.awaitValue()
                                release.succeed(Unit)
                                next.await() shouldBe listOf<Byte>(3, 4, 5)
                            } finally {
                                release.succeed(Unit)
                                first.cancel()
                            }
                        }
                    }
                }
                flights.closeAndJoin()
                loads.peek() shouldBe 1
                source.all { it == 0.toByte() } shouldBe true
            }
        }

        test("a retired pre-entry group cannot load or disturb a newer same-key group") {
            val freshSource = byteArrayOf(6, 7, 8)
            withLoadGroups(listOf(freshSource)) { ledger, flights, key, captured ->
                val oldLoads = Shared(0)
                val retired = Shared(false)
                val entered = Promise<Nothing, Unit>()
                val release = Promise<Nothing, Unit>()
                val ended = Promise<Nothing, Unit>()
                clock.locally(SystemClock) {
                    timeout(10.seconds) {
                        scope {
                            val first = async {
                                ledger.enrol(key, captured, groupDeadline).use { receipt ->
                                    flights.run(receipt.group) {
                                        entered.succeed(Unit)
                                        release.awaitValue()
                                        try {
                                            ledger.settle(receipt) {
                                                oldLoads.update { it + 1 }
                                                Either.Left(KeyError.Unavailable("retired supplier must not run"))
                                            }
                                        } catch (cancelled: CancellationException) {
                                            cancelled.message shouldBe "the key load is no longer admitted"
                                            retired.update { true }
                                            throw cancelled
                                        } finally {
                                            ended.succeed(Unit)
                                        }
                                    }
                                }
                            }
                            try {
                                entered.awaitValue()
                                first.cancel()
                                ledger.enrol(key, captured, groupDeadline).use { fresh ->
                                    flights.run(fresh.group) {
                                        ledger.settle(fresh) {
                                            Either.Right(DataKey(KeyOwner.Platform, KeyPurpose.Enc, 1, freshSource))
                                        }
                                    } shouldBe Either.Right(Unit)
                                    release.succeed(Unit)
                                    ended.awaitValue()
                                    retired.peek() shouldBe true
                                    oldLoads.peek() shouldBe 0
                                    freshSource.toList() shouldBe listOf<Byte>(6, 7, 8)
                                }
                            } finally {
                                release.succeed(Unit)
                                first.cancel()
                            }
                        }
                    }
                }
                flights.closeAndJoin()
                freshSource.all { it == 0.toByte() } shouldBe true
            }
        }

        test("claim bookkeeping cannot recursively wait on its own group") {
            withLoadGroups { ledger, flights, key, captured ->
                val loads = Shared(0)
                resourceScope {
                    val receipt = ledger.enrol(key, captured, groupDeadline).bind()
                    onLedgerCommit(0, {
                        runGroup(ledger, flights, key, captured) { error("recursive supplier must not enter") }
                    }) {
                        shouldThrow<IllegalStateException>({
                            clock.locally(SystemClock) {
                                timeout(1.seconds) {
                                    flights.run(receipt.group) {
                                        ledger.settle(receipt) {
                                            loads.update { it + 1 }
                                            Either.Left(KeyError.Unavailable("must not load"))
                                        }
                                    }
                                }
                            }
                        }) { it.message shouldBe "recursive key load" }
                    }
                    loads.peek() shouldBe 0
                }
            }
        }

        test("qualified metadata refusal wipes the transferred source without publishing a delivery") {
            val source = byteArrayOf(3, 4, 5)
            withLoadGroups(listOf(source)) { ledger, flights, legacy, captured ->
                val identity = KeyIdentity(KeyOwner.Platform, KeyPurpose.Enc, 1, byteArrayOf(1))
                val answer = runGroup(ledger, flights, legacy.copy(identity = identity), captured) {
                    Either.Right(DataKey(KeyOwner.Platform, KeyPurpose.Bidx, 1, source))
                }
                (answer is Either.Left && answer.value is KeyError.Invalid) shouldBe true
                source.all { it == 0.toByte() } shouldBe true
            }
        }
    }
}

internal object RetainedKeyDeliverySuite : Suite(concurrent = false, isolated = true) {
    init {
        test("a returned copy survives release of its source") { returnedCopySurvives() }
        test("a caller release after preparation aborts its exact copy") {
            val source = byteArrayOf(3, 4, 5)
            val seen = Shared<ByteArray?>(null)
            val marker = IllegalStateException("caller receipt release")
            withLoadGroups(listOf(source)) { ledger, flights, key, captured ->
                val callers = KeyCallers(flights)
                val held = resource {
                    onRelease { _ -> throw marker }
                    ledger.enrol(key, captured, groupDeadline).bind()
                }
                resourceScope {
                    onRelease { _ -> seen.peek()?.fill(0) }
                    shouldThrow<IllegalStateException>({
                        atDeliveryCommit({ stack -> stack.any { it.methodName == "registerPrepared" } }, {
                            val state = privateField(ledger, "state") as Shared<*>
                            val rows = privateField(state.peek(), "prepared") as List<*>
                            seen.update { snapshotPreparedCopy(rows) }
                        }) {
                            KeyCacheLedger.DeliveryCall.run(callers, ledger, held) {
                                Either.Right(DataKey(KeyOwner.Platform, KeyPurpose.Enc, 1, source))
                            }
                        }
                    }) { (it === marker) shouldBe true }
                    callers.closeAndAwait()
                    checkNotNull(seen.peek()).toList() shouldBe listOf<Byte>(0, 0, 0)
                }
            }
        }
        test("a refused qualified delivery aborts its registered row under the caller owner") {
            val source = byteArrayOf(3, 4, 5)
            val aborted = Shared(false)
            withLoadGroups(listOf(source)) { ledger, flights, legacy, captured ->
                val callers = KeyCallers(flights)
                val key = deliveryKey(legacy, true)
                val answer = atDeliveryCommit({ stack -> stack.any { it.methodName == "abortPrepared" } }, {
                    shouldThrow<IllegalStateException>({ callers.closeAndAwait() }) {
                        it.message shouldBe "a key cache caller cannot close its owner"
                    }
                    aborted.update { true }
                }) {
                    revokePreparedView(ledger) {
                        KeyCacheLedger.DeliveryCall.run(callers, ledger, ledger.enrol(key, captured, groupDeadline)) {
                            Either.Right(DataKey(KeyOwner.Platform, KeyPurpose.Enc, 1, source))
                        }
                    }
                }
                answer shouldBeLeft KeyError.Denied("the key view is unavailable")
                aborted.peek() shouldBe true
                source.all { it == 0.toByte() } shouldBe true
                val state = privateField(ledger, "state") as Shared<*>
                (privateField(state.peek(), "prepared") as List<*>).isEmpty() shouldBe true
                callers.closeAndAwait()
            }
        }
        test("a caller release failure retains the exact abort that also failed") {
            val source = byteArrayOf(3, 4, 5)
            val original = IllegalStateException("caller receipt release")
            val cleanup = IllegalArgumentException("caller view cleanup")
            withLoadGroups(listOf(source)) { ledger, flights, legacy, captured ->
                val callers = KeyCallers(flights)
                val held = resource {
                    onRelease { _ -> throw original }
                    ledger.enrol(deliveryKey(legacy, true), captured, groupDeadline).bind()
                }
                shouldThrow<IllegalStateException>({
                    atDeliveryCommit({ stack ->
                        stack.any { it.methodName == "abortPrepared" } &&
                            stack.any { it.className.endsWith("OwnedKeyMaterial") }
                    }, { throw cleanup }) {
                        KeyCacheLedger.DeliveryCall.run(callers, ledger, held) {
                            Either.Right(DataKey(KeyOwner.Platform, KeyPurpose.Enc, 1, source))
                        }
                    }
                }) {
                    (it === original) shouldBe true
                    (it.suppressed.singleOrNull() === cleanup) shouldBe true
                }
                val state = privateField(ledger, "state") as Shared<*>
                (privateField(state.peek(), "pending") as List<*>).size shouldBe 1
                ledger.drain()
                (privateField(state.peek(), "pending") as List<*>).isEmpty() shouldBe true
                source.all { it == 0.toByte() } shouldBe true
                callers.closeAndAwait()
            }
        }
        test("caller abort keeps a cleanup failure already reachable from the primary") {
            val source = byteArrayOf(3, 4, 5)
            val original = IllegalStateException("caller receipt release")
            val prior = IllegalArgumentException("prior cleanup")
            val cleanup = IllegalArgumentException("caller view cleanup")
            prior.addSuppressed(cleanup)
            original.addSuppressed(prior)
            withLoadGroups(listOf(source)) { ledger, flights, legacy, captured ->
                val callers = KeyCallers(flights)
                val held = resource {
                    onRelease { _ -> throw original }
                    ledger.enrol(deliveryKey(legacy, true), captured, groupDeadline).bind()
                }
                shouldThrow<IllegalStateException>({
                    atDeliveryCommit({ stack ->
                        stack.any { it.methodName == "abortPrepared" } &&
                            stack.any { it.className.endsWith("OwnedKeyMaterial") }
                    }, { throw cleanup }) {
                        KeyCacheLedger.DeliveryCall.run(callers, ledger, held) {
                            Either.Right(DataKey(KeyOwner.Platform, KeyPurpose.Enc, 1, source))
                        }
                    }
                }) {
                    (it === original) shouldBe true
                    (it.suppressed.singleOrNull() === prior) shouldBe true
                    (prior.suppressed.singleOrNull() === cleanup) shouldBe true
                }
                val state = privateField(ledger, "state") as Shared<*>
                (privateField(state.peek(), "pending") as List<*>).size shouldBe 1
                ledger.drain()
                (privateField(state.peek(), "pending") as List<*>).isEmpty() shouldBe true
                callers.closeAndAwait()
            }
        }

        test("a clean qualified refusal surfaces its exact abort failure") {
            val source = byteArrayOf(3, 4, 5)
            val cleanup = IllegalStateException("refused view cleanup")
            withLoadGroups(listOf(source)) { ledger, flights, legacy, captured ->
                val callers = KeyCallers(flights)
                val held = ledger.enrol(deliveryKey(legacy, true), captured, groupDeadline)
                shouldThrow<IllegalStateException>({
                    atDeliveryCommit({ stack ->
                        stack.any { it.methodName == "abortPrepared" } &&
                            stack.any { it.className.endsWith("OwnedKeyMaterial") }
                    }, { throw cleanup }) {
                        revokePreparedView(ledger) {
                            KeyCacheLedger.DeliveryCall.run(callers, ledger, held) {
                                Either.Right(DataKey(KeyOwner.Platform, KeyPurpose.Enc, 1, source))
                            }
                        }
                    }
                }) { (it === cleanup) shouldBe true }
                val state = privateField(ledger, "state") as Shared<*>
                (privateField(state.peek(), "pending") as List<*>).size shouldBe 1
                ledger.drain()
                (privateField(state.peek(), "pending") as List<*>).isEmpty() shouldBe true
                callers.closeAndAwait()
            }
        }

        test("a delivered caller view survives reaping its exact prepared row") {
            val source = byteArrayOf(3, 4, 5)
            withLoadGroups(listOf(source)) { ledger, flights, legacy, captured ->
                val callers = KeyCallers(flights)
                val held = ledger.enrol(deliveryKey(legacy, true), captured, groupDeadline)
                val answer = KeyCacheLedger.DeliveryCall.run(callers, ledger, held) {
                    Either.Right(DataKey(KeyOwner.Platform, KeyPurpose.Enc, 1, source))
                }
                val view = ((answer as Either.Right).value as KeyCacheLedger.Delivery.View).view
                resourceScope {
                    onRelease { _ -> view.close() }
                    ledger.drain()
                    val state = privateField(ledger, "state") as Shared<*>
                    (privateField(state.peek(), "prepared") as List<*>).isEmpty() shouldBe true
                    view.using { it.toList() } shouldBe Either.Right(listOf<Byte>(3, 4, 5))
                }
                source.all { it == 0.toByte() } shouldBe true
                callers.closeAndAwait()
            }
        }
        test("a returned view survives forgetting its source") { returnedViewSurvives() }
        test("receipt cleanup failure wipes a legacy copy that was never returned") { abandonedCopyWipes() }
        test("receipt cleanup failure closes a qualified view that was never returned") { abandonedViewCloses() }
        test("a preparing copy survives a drain before return") { preparingDrain(false) }
        test("a preparing view survives a drain before admission") { preparingDrain(true) }
        test("a delivered copy survives metadata reap failure") { deliveredReapFault(false) }
        test("a delivered view survives metadata reap failure") { deliveredReapFault(true) }
        test("a committed copy registration remains owned when its observer throws") {
            registrationObserverFault(false)
        }
        test("a committed view registration remains owned when its observer throws") { registrationObserverFault(true) }
        test("an admitted view remains owned when admission instrumentation throws") { admissionObserverFault() }
        test("an abort transfer observer failure still releases its exact view") { abortObserverFault("transfer") }
        test("an abort action observer failure stays pending for later drain") { abortObserverFault("action") }
        test("a committed abort acknowledgement is never resurrected") { abortObserverFault("ack") }
        test("stale prepared cleanup leaves a newer same-key delivery intact") { stalePreparedCleanup() }
        test("a typed refusal never becomes a delivered value") { leftIsNotDelivery() }
        test("receipt cancellation aborts the copy with its original identity") { cancellationAbortsCopy() }
        test("failed abort transfer and close before commit keep the exact capability owned") {
            bothAbortPrecommitFailures()
        }
        test("delivery supplier recursion refuses without orphaning another receipt") { deliveryRecursionRefuses() }
        test("copy registration interruption before commit releases its local ownership") {
            registrationPrecommitFault(false)
        }
        test("view registration interruption before commit admits no view") { registrationPrecommitFault(true) }
    }
}

object RetainedKeyCallersSuite : Suite(concurrent = false, isolated = true) {
    init {
        test("a failed nested caller restores its outer owner binding") {
            val callers = KeyCallers(SingleFlight())
            val original = IllegalStateException("nested caller failure")
            callers.call {
                shouldThrow<IllegalStateException>({
                    callers.call { throw original }
                }) { (it === original) shouldBe true }
                shouldThrow<IllegalStateException>({ callers.closeAndAwait() }) {
                    it.message shouldBe "a key cache caller cannot close its owner"
                }
            }
            callers.closeAndAwait()
        }

        test("an accepted body owns its Resource release before call returns") {
            val callers = KeyCallers(SingleFlight())
            val events = Shared(emptyList<String>())
            callers.call {
                resourceScope {
                    onRelease { _ -> events.update { it + "release" } }
                    events.update { it + "body" }
                }
            }
            events.update { it + "returned" }
            events.peek() shouldBe listOf("body", "release", "returned")
        }

        listOf(false, true).forEach { unwind ->
            test("an accepted caller stays held through body or Resource release: $unwind") {
                val probe = PendingCallerProbe()
                val callers = KeyCallers(SingleFlight())
                val callback = HandoffDecision<Unit>()
                val returned = HandoffDecision<Unit>()
                scope {
                    resourceScope {
                        onRelease { _ -> probe.gate.adjust(1.seconds) }
                        val work = async {
                            probe.call(callers, unwind) { callback.tryComplete(Unit) }
                            returned.tryComplete(Unit)
                        }
                        probe.awaitHeld()
                        callback.peek() shouldBe null
                        returned.peek() shouldBe null
                        probe.finish(work)
                    }
                }
                callback.peek() shouldBe Unit
                returned.peek() shouldBe Unit
            }
        }

        test("a committed admission observer throw preserves original and runs no body") {
            val callers = KeyCallers(SingleFlight())
            val marker = IllegalStateException("synthetic admission observer")
            val body = HandoffDecision<Unit>()
            atDeliveryCommit({ stack -> stack.any { it.methodName == "admit" } }, { throw marker }) {
                shouldThrow<IllegalStateException>({ callers.call { body.tryComplete(Unit) } }) {
                    (it === marker) shouldBe true
                }
            }
            body.peek() shouldBe null
            callers.call { body.tryComplete(Unit) }
            body.peek() shouldBe Unit
        }

        test("ordinary interrupt and cancellation retain ordered owned releases") {
            val ordinary = IllegalStateException("synthetic caller")
            val interrupted = InterruptedException("synthetic caller")
            val cancelled = CancellationException("synthetic caller")
            listOf(
                ordinary to ExitCase.Failure(ordinary),
                interrupted to ExitCase.Cancelled(interrupted),
                cancelled to ExitCase.Completed,
            ).forEach { (primary, exit) -> callerFailure(primary, exit) }
        }

        test("a suppression-disabled original retains identity and all ordered release attempts") {
            val callers = KeyCallers(SingleFlight())
            val primary = object : Throwable("synthetic caller", null, false, false) {}
            val first = IllegalStateException("synthetic outer release")
            val second = IllegalStateException("synthetic inner release")
            val releases = Shared(emptyList<Pair<Int, ExitCase>>())
            shouldThrow<Throwable>({
                callers.call {
                    resourceScope {
                        onRelease { exit ->
                            releases.update { it + (1 to exit) }
                            throw first
                        }
                        onRelease { exit ->
                            releases.update { it + (2 to exit) }
                            throw second
                        }
                        throw primary
                    }
                }
            }) { (it === primary) shouldBe true }
            releases.peek() shouldBe listOf(2 to ExitCase.Failure(primary), 1 to ExitCase.Failure(primary))
            primary.suppressed.toList() shouldBe emptyList()
        }

        test("a release failure after normal body remains the original failure") {
            val callers = KeyCallers(SingleFlight())
            val primary = IllegalStateException("synthetic release")
            val exits = Shared(emptyList<ExitCase>())
            shouldThrow<IllegalStateException>({
                callers.call {
                    resourceScope {
                        onRelease { exit ->
                            exits.update { it + exit }
                            throw primary
                        }
                    }
                }
            }) { (it === primary) shouldBe true }
            exits.peek() shouldBe listOf(ExitCase.Completed)
        }

        test("an opaque Fail crosses caller and foreign attempt with cleanup intact") {
            val callers = KeyCallers(SingleFlight())
            val error = IllegalArgumentException("synthetic declared error")
            val prior = IllegalStateException("synthetic prior diagnostic")
            val first = IllegalStateException("synthetic outer release")
            val second = IllegalStateException("synthetic inner release")
            val original = HandoffDecision<Throwable>()
            val foreignTail = HandoffDecision<Unit>()
            val events = Shared(emptyList<Int>())
            val exits = Shared(emptyList<ExitCase>())
            error.addSuppressed(prior)
            val answer = attempt<Throwable, Unit> {
                val owner = this
                shouldThrow<Throwable>({
                    attempt<String, Unit> {
                        callers.call {
                            resourceScope {
                                onRelease { exit ->
                                    events.update { it + 1 }
                                    exits.update { it + exit }
                                    throw first
                                }
                                onRelease { exit ->
                                    events.update { it + 2 }
                                    exits.update { it + exit }
                                    throw second
                                }
                                shouldThrow<Throwable>({ owner.fail(error) }) { signal ->
                                    original.tryComplete(signal)
                                    signal.stackTrace.toList() shouldBe emptyList()
                                    throw signal
                                }
                            }
                        }
                        foreignTail.tryComplete(Unit)
                    }
                }) { crossed ->
                    (crossed === original.peek()) shouldBe true
                    crossed.stackTrace.toList() shouldBe emptyList()
                    crossed.suppressed.toList() shouldBe listOf(second, first)
                    throw crossed
                }
            }
            answer.shouldBeLeft { (it === error) shouldBe true }
            error.suppressed.toList() shouldBe listOf(prior)
            events.peek() shouldBe listOf(2, 1)
            exits.peek() shouldBe listOf(ExitCase.Completed, ExitCase.Completed)
            foreignTail.peek() shouldBe null
        }

        test("the call guard refuses ordinary nested and forced solo steps before admission") {
            listOf("ordinary", "nested", "solo").forEach { mode -> callerGuard(mode) }
        }

        test("call refusal preserves an already entered step and interrupt flag") {
            val callers = KeyCallers(SingleFlight())
            val thread = Thread.currentThread()
            val before = thread.isInterrupted
            val value = Shared(0)
            val body = HandoffDecision<Unit>()
            try {
                Thread.interrupted()
                transact {
                    thread.interrupt()
                    shouldThrow<IllegalStateException>({ callers.call { body.tryComplete(Unit) } }) {
                        it.message shouldBe "the operation cannot run inside a transaction"
                    }
                    thread.isInterrupted shouldBe true
                    Thread.interrupted()
                    value.set(1)
                }
                value.peek() shouldBe 1
                body.peek() shouldBe null
            } finally {
                Thread.interrupted()
                if (before) thread.interrupt()
            }
        }

        test("caller failure preserves a cyclic existing suppressed graph") {
            val callers = KeyCallers(SingleFlight())
            val primary = IllegalStateException("synthetic primary")
            val nested = IllegalStateException("synthetic nested")
            val release = IllegalStateException("synthetic release")
            primary.addSuppressed(nested)
            nested.addSuppressed(primary)
            shouldThrow<IllegalStateException>({
                callers.call {
                    resourceScope {
                        onRelease { _ -> throw release }
                        throw primary
                    }
                }
            }) {
                (it === primary) shouldBe true
                it.suppressed.toList() shouldBe listOf(nested, release)
                nested.suppressed.toList() shouldBe listOf(primary)
            }
        }

        test("the Solo probe interrupts a real pending Shared commit without changing its value") {
            val probe = CallerFenceProbe()
            val state = Shared(0)
            val failed = HandoffDecision<InterruptedException>()
            withCallerFenceContention(probe, { state.peek() shouldBe 0 }) {
                shouldThrow<InterruptedException>({ state.update { it + 1 } }) {
                    failed.tryComplete(it)
                    it.stackTrace.any { frame -> frame.methodName == "awaitFree" } shouldBe true
                    it.stackTrace.any { frame -> frame.methodName == "commit\$dev_surefold_surefold" } shouldBe true
                }
                Thread.currentThread().isInterrupted shouldBe false
            }
            (failed.peek() != null) shouldBe true
            state.peek() shouldBe 0
            state.update { it + 1 }
            state.peek() shouldBe 1
            probe.target.awaitValue().isAlive shouldBe false
            probe.holder.awaitValue().isAlive shouldBe false
        }

        test("a fresh commit after the Solo interruption survives its observer failure and owned unwind") {
            val probe = CallerFenceProbe()
            val state = Shared(0)
            val observed = HandoffDecision<Int>()
            val released = HandoffDecision<ExitCase>()
            val marker = IllegalStateException("synthetic probe observer")
            withCallerFenceContention(probe, { state.peek() shouldBe 0 }) {
                shouldThrow<InterruptedException>({ state.update { it + 1 } }) {
                    it.stackTrace.any { frame -> frame.methodName == "awaitFree" } shouldBe true
                }
                shouldThrow<IllegalStateException>({
                    resourceScope {
                        onRelease { exit -> released.tryComplete(exit) }
                        atDeliveryCommit({ stack -> stack.any { it.methodName == "modify" } }, {
                            observed.tryComplete(state.peek())
                            throw marker
                        }) { state.modify { it + 1 to Unit } }
                    }
                }) { (it === marker) shouldBe true }
            }
            observed.peek() shouldBe 1
            state.peek() shouldBe 1
            released.peek() shouldBe ExitCase.Failure(marker)
        }

        test("an assertion failure while Solo is held releases every gate and joins both workers") {
            val probe = CallerFenceProbe()
            val state = Shared(0)
            val marker = AssertionError("synthetic probe assertion")
            shouldThrow<AssertionError>({
                withCallerFenceContention(probe, { throw marker }) { state.update { it + 1 } }
            }) { (it === marker) shouldBe true }
            probe.target.awaitValue().isAlive shouldBe false
            probe.holder.awaitValue().isAlive shouldBe false
            probe.blocker.sleepers() shouldBe 0
            probe.holderStart.sleepers() shouldBe 0
            probe.targetStart.sleepers() shouldBe 0
            probe.pause.sleepers() shouldBe 0
            state.peek() shouldBe 0
            state.update { it + 1 }
            state.peek() shouldBe 1
        }

        listOf(false, true).forEach { stop ->
            test("pause-only handoffs retain late state and clean up after assertion: $stop") {
                val probe = CallerFenceProbe()
                val start = TestClock(Instant.EPOCH)
                val hold = TestClock(Instant.EPOCH)
                val entered = Promise<Nothing, Unit>()
                val captured = HandoffDecision<Int>()
                val handoffs = Shared(0)
                val state = Shared(0)
                val marker = AssertionError("synthetic after-admission assertion")
                scope {
                    resourceScope {
                        onRelease { _ ->
                            start.adjust(1.seconds)
                            hold.adjust(1.seconds)
                        }
                        val late = async {
                            start.sleep(1.seconds)
                            state.update { it + 1 }
                            entered.succeed(Unit)
                            hold.sleep(1.seconds)
                        }
                        eventually(5.seconds) { start.sleepers() shouldBe 1 }
                        val exercise = {
                            withCallerFenceContention(
                                probe, { state.peek() shouldBe 0 },
                                interruptAfterPause = false,
                                afterHolder = {
                                    onRelease { _ ->
                                        start.adjust(1.seconds)
                                        hold.adjust(1.seconds)
                                    }
                                    handoffs.update { it + 1 }
                                    start.adjust(1.seconds)
                                    entered.awaitValue()
                                    eventually(5.seconds) { hold.sleepers() shouldBe 1 }
                                },
                                afterResume = {
                                    val target = probe.target.awaitValue()
                                    eventually(5.seconds) {
                                        (captured.peek() != null) shouldBe true
                                        target.state shouldBe Thread.State.WAITING
                                        (LockSupport.getBlocker(target) === late) shouldBe true
                                    }
                                    captured.peek() shouldBe 1
                                    handoffs.peek() shouldBe 1
                                    hold.sleepers() shouldBe 1
                                    if (stop) throw marker
                                    hold.adjust(1.seconds)
                                },
                            ) {
                                captured.tryComplete(state.modify { it + 10 to it })
                                late.await()
                            }
                        }
                        if (stop) shouldThrow<AssertionError>({ exercise() }) { (it === marker) shouldBe true }
                        else exercise()
                        late.await()
                    }
                }
                captured.peek() shouldBe 1
                state.peek() shouldBe 11
                probe.target.awaitValue().isAlive shouldBe false
                probe.holder.awaitValue().isAlive shouldBe false
                listOf(start, hold, probe.blocker, probe.holderStart, probe.targetStart, probe.pause).forEach {
                    it.sleepers() shouldBe 0
                }
            }
        }
    }
}

object RetainedKeyCallersCloseSuite : Suite(concurrent = false, isolated = true) {
    init {
        test("close retries an interrupted acknowledgement without repeating its action") {
            interruptedCloseAcknowledgement()
        }

        test("a defect after close transfers a prepared abort leaves its exact action pending") {
            val source = byteArrayOf(3, 4, 5)
            val original = IllegalStateException("synthetic committed group cleanup")
            val transferFault = IllegalStateException("close transfer committed")
            val later = Shared(0)
            withLoadGroups(listOf(source)) { ledger, flights, legacy, captured ->
                heldSoloDeliveryFault(false, original) {
                    ledger.deliver(deliveryKey(legacy, true), captured, groupDeadline, flights) {
                        Either.Right(DataKey(KeyOwner.Platform, KeyPurpose.Enc, 1, source))
                    }
                }
                ledger.owe { later.update { it + 1 } }
                shouldThrow<IllegalStateException>({
                    atDeliveryCommit({ stack -> stack.any { it.methodName == "transferPrepared" } }, {
                        throw transferFault
                    }) { KeyCallers(flights).closeAndAwait(ledger) }
                }) { (it === transferFault) shouldBe true }
                source.any { it != 0.toByte() } shouldBe true
                later.peek() shouldBe 1
                ledger.drain()
                source.all { it == 0.toByte() } shouldBe true
            }
        }

        test("close reaps delivered metadata and leaves its returned copy live") {
            val source = byteArrayOf(3, 4, 5)
            withLoadGroups(listOf(source)) { ledger, flights, key, captured ->
                val callers = KeyCallers(flights)
                val held = ledger.enrol(key, captured, groupDeadline)
                val answer = KeyCacheLedger.DeliveryCall.run(callers, ledger, held) {
                    Either.Right(DataKey(KeyOwner.Platform, KeyPurpose.Enc, 1, source))
                }
                val copy = ((answer as Either.Right).value as KeyCacheLedger.Delivery.Copy).key.material
                resourceScope {
                    onRelease { _ -> copy.fill(0) }
                    callers.closeAndAwait(ledger)
                    val state = privateField(ledger, "state") as Shared<*>
                    (privateField(state.peek(), "prepared") as List<*>).isEmpty() shouldBe true
                    copy.toList() shouldBe listOf<Byte>(3, 4, 5)
                }
            }
        }

        test("close keeps its snapshot and an acknowledged action is never replayed") {
            val ledger = KeyCacheLedger()
            val callers = KeyCallers(SingleFlight())
            val actions = Shared(emptyList<String>())
            resourceScope {
                onRelease { _ -> Thread.interrupted() }
                ledger.owe {
                    val first = actions.modify { it + "action" to it.isEmpty() }
                    if (first) {
                        ledger.owe { actions.update { it + "new" } }
                        throw InterruptedException("interrupt action before return")
                    }
                }
                ledger.owe { actions.update { it + "later" } }
                atDeliveryCommit({ stack -> stack.any { it.methodName == "acknowledge" } }, {
                    throw InterruptedException("interrupt after acknowledgement commits")
                }) { callers.closeAndAwait(ledger) }
                Thread.interrupted() shouldBe true
                actions.peek() shouldBe listOf("action", "action", "later")
                ledger.drain()
                actions.peek() shouldBe listOf("action", "action", "later", "new")
            }
        }

        test("a mixed close fault leaves that intent pending and attempts the other intent") {
            val ledger = KeyCacheLedger()
            val callers = KeyCallers(SingleFlight())
            val attempts = Shared(0)
            val later = Shared(0)
            val defect = IllegalStateException("close cleanup defect")
            val mixed = InterruptedException("mixed cleanup").apply { addSuppressed(defect) }
            resourceScope {
                onRelease { _ -> Thread.interrupted() }
                ledger.owe {
                    if (attempts.modify { it + 1 to (it == 0) }) throw mixed
                }
                ledger.owe { later.update { it + 1 } }
                shouldThrow<IllegalStateException>({ callers.closeAndAwait(ledger) }) { (it === defect) shouldBe true }
                Thread.interrupted() shouldBe true
                attempts.peek() shouldBe 1
                later.peek() shouldBe 1
                ledger.drain()
                attempts.peek() shouldBe 2
                later.peek() shouldBe 1
            }
        }

        test("close does not retry an interrupted action after another drain acknowledges it") {
            val ledger = KeyCacheLedger()
            val callers = KeyCallers(SingleFlight())
            val attempts = Shared(0)
            val later = Shared(0)
            val entered = Promise<Nothing, Unit>()
            val resume = Promise<Nothing, Unit>()
            val leaveLaterPending = IllegalStateException("leave the other captured item pending")
            scope {
                resourceScope {
                    onRelease { _ -> resume.succeed(Unit) }
                    ledger.owe {
                        if (attempts.modify { it + 1 to (it == 0) }) {
                            entered.succeed(Unit)
                            resume.awaitValue()
                            throw InterruptedException("first close attempt")
                        }
                    }
                    ledger.owe {
                        if (later.modify { it + 1 to (it == 0) }) throw leaveLaterPending
                    }
                    val closing = async {
                        callers.closeAndAwait(ledger)
                        Thread.interrupted()
                    }
                    entered.awaitValue()
                    shouldThrow<IllegalStateException>({ ledger.drain() }) { (it === leaveLaterPending) shouldBe true }
                    resume.succeed(Unit)
                    closing.await() shouldBe true
                    ledger.drain()
                    attempts.peek() shouldBe 2
                    later.peek() shouldBe 2
                }
            }
        }

        listOf(false, true).forEach { stop ->
            test("atomic caller fence captures late admission and cleans up after assertion: $stop") {
                val probe = CallerFenceProbe()
                val callers = KeyCallers(SingleFlight())
                val start = TestClock(Instant.EPOCH)
                val hold = TestClock(Instant.EPOCH)
                val entered = Promise<Nothing, Unit>()
                val returned = HandoffDecision<Unit>()
                val marker = AssertionError("synthetic held-caller admission assertion")
                scope {
                    resourceScope {
                        onRelease { _ ->
                            start.adjust(1.seconds)
                            hold.adjust(1.seconds)
                        }
                        val late = async {
                            start.sleep(1.seconds)
                            callers.call {
                                entered.succeed(Unit)
                                hold.sleep(1.seconds)
                            }
                        }
                        eventually(5.seconds) { start.sleepers() shouldBe 1 }
                        val exercise = {
                            withCallerFenceContention(
                                probe, {
                                    val stack = probe.target.awaitValue().stackTrace
                                    stack.any { it.methodName == "fence" } shouldBe true
                                    stack.any { it.methodName == "closeAndAwait" } shouldBe true
                                },
                                interruptAfterPause = false,
                                afterHolder = {
                                    onRelease { _ ->
                                        start.adjust(1.seconds)
                                        hold.adjust(1.seconds)
                                    }
                                    start.adjust(1.seconds)
                                    entered.awaitValue()
                                    eventually(5.seconds) { hold.sleepers() shouldBe 1 }
                                },
                                afterResume = {
                                    val target = probe.target.awaitValue()
                                    eventually(5.seconds) {
                                        probe.pause.sleepers() shouldBe 0
                                        hold.sleepers() shouldBe 1
                                        val stack = target.stackTrace
                                        val observing = target.state == Thread.State.TIMED_WAITING &&
                                            LockSupport.getBlocker(target) != null &&
                                            stack.any { it.methodName == "observe" } &&
                                            stack.any { it.methodName == "closeAndAwait" }
                                        (returned.peek() != null || observing) shouldBe true
                                    }
                                    returned.peek() shouldBe null
                                    if (stop) throw marker
                                    hold.adjust(1.seconds)
                                },
                            ) {
                                callers.closeAndAwait()
                                returned.tryComplete(Unit)
                            }
                        }
                        if (stop) shouldThrow<AssertionError>({ exercise() }) { (it === marker) shouldBe true }
                        else exercise()
                        late.await()
                    }
                }
                returned.peek() shouldBe Unit
                probe.target.awaitValue().isAlive shouldBe false
                probe.holder.awaitValue().isAlive shouldBe false
                listOf(start, hold, probe.blocker, probe.holderStart, probe.targetStart, probe.pause).forEach {
                    it.sleepers() shouldBe 0
                }
                shouldThrow<IllegalStateException>({ callers.call { throw AssertionError("closed body ran") } }) {
                    it.message shouldBe "the key cache is closed"
                }
            }
        }

        listOf(false, true).forEach { unwind ->
            test("accepted callers keep close parked through ${if (unwind) "Resource unwind" else "body entry"}") {
                val probe = PendingCallerProbe()
                pendingCaller(probe, unwind = unwind, inspect = { _, closer ->
                    LockSupport.unpark(closer)
                    closer.interrupt()
                    eventually(5.seconds) { closer.isInterrupted shouldBe false }
                    eventually(5.seconds) { closer.state shouldBe Thread.State.TIMED_WAITING }
                    probe.ended.peek() shouldBe null
                })
                probe.ended.peek() shouldBe true
            }
        }

        test("two closers preserve the same pending caller until it finishes") {
            val probe = PendingCallerProbe()
            val secondThread = Promise<Nothing, Thread>()
            val secondDone = HandoffDecision<Unit>()
            val join = HandoffDecision<() -> Unit>()
            scope {
                pendingCaller(probe, inspect = { callers, _ ->
                    val second = async {
                        secondThread.succeed(Thread.currentThread())
                        clock.locally(fixedClock()) { callers.closeAndAwait() }
                        secondDone.tryComplete(Unit)
                    }
                    join.tryComplete { second.await() }
                    val closer = secondThread.awaitValue()
                    eventually(5.seconds) { closer.state shouldBe Thread.State.TIMED_WAITING }
                    secondDone.peek() shouldBe null
                })
                checkNotNull(join.peek()).invoke()
                secondDone.peek() shouldBe Unit
            }
        }

        test("a real precommit fence interruption clears before retry and restores at close exit") {
            val probe = CallerFenceProbe()
            val callers = KeyCallers(SingleFlight())
            val committed = HandoffDecision<Unit>()
            val flag = HandoffDecision<Boolean>()
            withCallerFenceContention(probe, {
                val stack = checkNotNull(probe.waiting.peek()).stackTrace
                stack.any { it.methodName == "closeAndAwait" } shouldBe true
                stack.any { it.methodName == "fence" } shouldBe true
                committed.peek() shouldBe null
            }) {
                atDeliveryCommit({ stack -> stack.any { it.methodName == "fence" } }, {
                    Thread.currentThread().isInterrupted shouldBe false
                    committed.tryComplete(Unit)
                }) { callers.closeAndAwait() }
                flag.tryComplete(Thread.currentThread().isInterrupted)
            }
            committed.peek() shouldBe Unit
            flag.peek() shouldBe true
            shouldThrow<IllegalStateException>({ callers.call {} }) { it.message shouldBe "the key cache is closed" }
        }

        listOf("plain", "cycle", "disabled", "cancellation").forEach { mode ->
            test("committed fence and reap failures retain received roots for $mode") {
                val probe = PendingCallerProbe()
                val nested = IllegalStateException("synthetic nested close failure")
                val primary: Throwable = when (mode) {
                    "disabled" -> object : Throwable("synthetic close", null, false, false) {}
                    "cancellation" -> CancellationException("synthetic close")
                    else -> InterruptedException("synthetic close")
                }
                if (mode == "plain" || mode == "cycle") primary.addSuppressed(nested)
                if (mode == "cycle") nested.addSuppressed(primary)
                val later = InterruptedException("synthetic committed reap")
                val expected = if (mode == "plain" || mode == "cycle") nested else primary
                pendingCaller(probe, invoke = { callers ->
                    atDeliveryCommit({ stack -> stack.any { it.methodName == "fence" } }, { throw primary }) {
                        atDeliveryCommit({ stack -> stack.any { it.methodName == "reap" } }, { throw later }) {
                            shouldThrow<Throwable>({ callers.closeAndAwait() }) { (it === expected) shouldBe true }
                        }
                    }
                })
                probe.ended.peek() shouldBe true
                if (mode == "disabled") primary.suppressed.toList() shouldBe emptyList()
                else (expected.suppressed.any { it === later }) shouldBe true
                if (mode == "plain" || mode == "cycle") (expected.suppressed.any { it === primary }) shouldBe true
            }
        }

        test("opaque Fail retains its received cleanup root before its owning attempt") {
            val probe = PendingCallerProbe()
            val error = Any()
            val release = IllegalStateException("synthetic reap after Fail")
            pendingCaller(probe, invoke = { callers ->
                val answer = attempt<Any, Unit> {
                    val owner = this
                    atDeliveryCommit({ stack -> stack.any { it.methodName == "fence" } }, { owner.fail(error) }) {
                        atDeliveryCommit({ stack -> stack.any { it.methodName == "reap" } }, { throw release }) {
                            shouldThrow<Throwable>({ callers.closeAndAwait() }) { signal ->
                                signal.stackTrace.toList() shouldBe emptyList()
                                signal.suppressed.toList() shouldBe listOf(release)
                                throw signal
                            }
                        }
                    }
                }
                answer.shouldBeLeft { (it === error) shouldBe true }
            })
        }

        test("same-owner direct inherited and observer closes refuse before fencing") {
            val flights = SingleFlight<Any, KeyError, Unit>()
            val callers = KeyCallers(flights)
            resourceScope {
                onRelease { _ -> flights.closeAndJoin() }
                val refuse = {
                    shouldThrow<IllegalStateException>({ callers.closeAndAwait() }) {
                        it.message shouldBe "a key cache caller cannot close its owner"
                    }
                }
                callers.call {
                    refuse()
                    flights.run(Any()) {
                        refuse()
                        Either.Right(Unit)
                    }
                }
                atDeliveryCommit({ stack -> stack.any { it.methodName == "admit" } }, refuse) { callers.call {} }
                atDeliveryCommit({ stack -> stack.any { it.methodName == "fence" } }, refuse) {
                    callers.closeAndAwait()
                }
            }
        }

        test("normal interrupted pool stop clears before actual caller observation and restores on exit") {
            val probe = PendingCallerProbe()
            val flights = SingleFlight<Any, KeyError, Unit>()
            val started = Promise<Nothing, Unit>()
            scope {
                val worker = async {
                    flights.run(Any()) {
                        shouldThrow<InterruptedException>({
                            resourceScope {
                                onRelease { _ -> probe.pool.sleep(1.seconds) }
                                started.succeed(Unit)
                                Promise<Nothing, Unit>().awaitValue()
                            }
                        }) {}
                        Either.Right(Unit)
                    }
                }
                started.awaitValue()
                pendingCaller(probe, flights = flights, beforeWait = { closer ->
                    eventually(5.seconds) { probe.pool.sleepers() shouldBe 1 }
                    eventually(5.seconds) { closer.state shouldBe Thread.State.WAITING }
                    closer.stackTrace.any { it.methodName == "closeAndJoin" } shouldBe true
                    closer.interrupt()
                    probe.pool.adjust(1.seconds)
                }, inspect = { _, closer -> closer.isInterrupted shouldBe false })
                worker.await()
            }
            probe.ended.peek() shouldBe true
            probe.pool.sleepers() shouldBe 0
        }

        test("a real same-pool stop error still observes callers and relays the same failure") {
            val probe = PendingCallerProbe()
            val failure = HandoffDecision<Throwable>()
            pendingCaller(probe, workerFailure = failure, invoke = { callers ->
                shouldThrow<IllegalStateException>({ callers.closeAndAwait() }) {
                    failure.tryComplete(it)
                    it.message shouldBe "A SingleFlight worker cannot close its owner"
                    it.stackTrace.any { frame -> frame.methodName == "closeAndJoin" } shouldBe true
                    throw it
                }
            })
            (failure.peek() != null) shouldBe true
        }

        listOf("ordinary", "nested", "solo").forEach { mode ->
            test("close refuses $mode steps before same-owner checks and preserves interruption") {
                val callers = KeyCallers(SingleFlight())
                val thread = Thread.currentThread()
                val before = thread.isInterrupted
                try {
                    callers.call {
                        val invoke: () -> Unit = {
                            thread.interrupt()
                            shouldThrow<IllegalStateException>({ callers.closeAndAwait() }) {
                                it.message shouldBe "the operation cannot run inside a transaction"
                            }
                            thread.isInterrupted shouldBe true
                            Thread.interrupted()
                        }
                        when (mode) {
                            "ordinary" -> transact { invoke() }
                            "nested" -> transact { transact { invoke() } }
                            "solo" -> transact(soloAfter = 0) { invoke() }
                            else -> throw CallerFenceProbeSetupFailure()
                        }
                    }
                    callers.call {}
                    callers.closeAndAwait()
                } finally {
                    Thread.interrupted()
                    if (before) thread.interrupt()
                }
            }
        }

        test("committed terminal reap is not repeated and an empty close has nothing to reap") {
            val callers = KeyCallers(SingleFlight())
            val first = HandoffDecision<Unit>()
            val again = HandoffDecision<Unit>()
            callers.call {}
            atDeliveryCommit({ stack -> stack.any { it.methodName == "reap" } }, { first.tryComplete(Unit) }) {
                callers.closeAndAwait()
            }
            atDeliveryCommit({ stack -> stack.any { it.methodName == "reap" } }, { again.tryComplete(Unit) }) {
                callers.closeAndAwait()
                KeyCallers(SingleFlight()).closeAndAwait()
            }
            first.peek() shouldBe Unit
            again.peek() shouldBe null
        }

        test("an assertion during pending observation releases every test gate and joins both workers") {
            val probe = PendingCallerProbe()
            val marker = AssertionError("synthetic pending-caller assertion")
            shouldThrow<AssertionError>({
                pendingCaller(probe, inspect = { _, _ -> throw marker })
            }) { (it === marker) shouldBe true }
            probe.caller.awaitValue().isAlive shouldBe false
            probe.closer.awaitValue().isAlive shouldBe false
            probe.gate.sleepers() shouldBe 0
            probe.pool.sleepers() shouldBe 0
        }
    }
}

private fun <A> onLedgerCommit(target: Int, action: () -> Unit, block: () -> A): A {
    val calls = Shared(0)
    val atCommit = object : Metrics by NoMetrics {
        override fun counter(name: String, tags: Map<String, String>): Counter = Counter {
            if (name == "surefold.transact.runs" && tags["outcome"] == "committed") {
                val index = metrics.locally(NoMetrics) { calls.modify { it + 1 to it } }
                if (index == target) metrics.locally(NoMetrics, action)
            }
        }
    }
    return metrics.locally(atCommit, block)
}

private fun runGroup(
    ledger: KeyCacheLedger,
    flights: SingleFlight<Any, KeyError, Unit>,
    key: KeyCacheLedger.LoadKey,
    clock: TestClock,
    load: () -> Either<KeyError, DataKey>,
): Either<KeyError, Unit> = ledger.enrol(key, clock, groupDeadline).use { receipt ->
    flights.run(receipt.group) { ledger.settle(receipt, load) }
}

private val groupDeadline: Instant = Instant.EPOCH.plusSeconds(3_600)

private fun <A> withLoadGroups(
    arrays: List<ByteArray> = emptyList(),
    block: (KeyCacheLedger, SingleFlight<Any, KeyError, Unit>, KeyCacheLedger.LoadKey, TestClock) -> A,
): A = resourceScope {
    onRelease { _ -> arrays.forEach { it.fill(0) } }
    val flights = SingleFlight<Any, KeyError, Unit>()
    onRelease { _ -> flights.closeAndJoin() }
    val key = KeyCacheLedger.LoadKey(KeyCache.Slot(KeyOwner.Platform, KeyPurpose.Enc, 1), null, Any())
    block(KeyCacheLedger(), flights, key, TestClock(Instant.EPOCH))
}

private fun recursiveGroup(indirect: Boolean) = withLoadGroups { ledger, flights, key, captured ->
    val nested = key.copy(slot = key.slot.copy(purpose = KeyPurpose.Bidx))
    val loads = Shared(0)
    val recurse: () -> Either<KeyError, DataKey> = {
        loads.update { it + 1 }
        runGroup(ledger, flights, key, captured) { error("recursive supplier must not enter") }
        error("recursive group must refuse")
    }
    shouldThrow<IllegalStateException>({
        clock.locally(SystemClock) {
            timeout(1.seconds) {
                runGroup(ledger, flights, key, captured) {
                    loads.update { it + 1 }
                    if (indirect) runGroup(ledger, flights, nested, captured, recurse)
                    else runGroup(ledger, flights, key, captured) { error("recursive supplier must not enter") }
                    error("recursive group must refuse")
                }
            }
        }
    }) { it.message shouldBe "recursive key load" }
    loads.peek() shouldBe if (indirect) 2 else 1
}

private fun <A> failCommitAfter(ready: Shared<Boolean>, marker: Throwable, block: () -> A): A {
    val fired = Shared(false)
    val failing = object : Metrics by NoMetrics {
        override fun counter(name: String, tags: Map<String, String>): Counter = Counter {
            val committed = name == "surefold.transact.runs" && tags["outcome"] == "committed"
            if (committed && ready.peek() && metrics.locally(NoMetrics) { fired.modify { true to it } }.not()) {
                throw marker
            }
        }
    }
    return metrics.locally(failing, block)
}

private fun normalGroupSource(qualified: Boolean) {
    val source = byteArrayOf(3, 4, 5)
    withLoadGroups(listOf(source)) { ledger, flights, legacy, captured ->
        val identity = if (qualified) KeyIdentity(KeyOwner.Platform, KeyPurpose.Enc, 1, byteArrayOf(1)) else null
        val key = legacy.copy(identity = identity)
        resourceScope {
            val receipt = ledger.enrol(key, captured, groupDeadline).bind()
            flights.run(receipt.group) {
                ledger.settle(receipt) {
                    Either.Right(DataKey(KeyOwner.Platform, KeyPurpose.Enc, 1, source))
                }
            } shouldBe Either.Right(Unit)
            source.toList() shouldBe listOf<Byte>(3, 4, 5)
        }
        source.all { it == 0.toByte() } shouldBe true
    }
}

private fun returnedCopySurvives() {
    val source = byteArrayOf(3, 4, 5)
    withLoadGroups(listOf(source)) { ledger, flights, key, captured ->
        val answer = ledger.deliver(key, captured, groupDeadline, flights) {
            Either.Right(DataKey(KeyOwner.Platform, KeyPurpose.Enc, 1, source))
        }
        val copy = ((answer as Either.Right).value as KeyCacheLedger.Delivery.Copy).key
        resourceScope {
            onRelease { _ -> copy.material.fill(0) }
            source.all { it == 0.toByte() } shouldBe true
            copy.material.toList() shouldBe listOf<Byte>(3, 4, 5)
            ledger.drain()
            copy.material.toList() shouldBe listOf<Byte>(3, 4, 5)
        }
    }
}

private fun returnedViewSurvives() {
    val source = byteArrayOf(3, 4, 5)
    withLoadGroups(listOf(source)) { ledger, flights, legacy, captured ->
        val identity = KeyIdentity(KeyOwner.Platform, KeyPurpose.Enc, 1, byteArrayOf(1))
        val answer = ledger.deliver(legacy.copy(identity = identity), captured, groupDeadline, flights) {
            Either.Right(DataKey(KeyOwner.Platform, KeyPurpose.Enc, 1, source))
        }
        val view = ((answer as Either.Right).value as KeyCacheLedger.Delivery.View).view
        resourceScope {
            onRelease { _ -> view.close() }
            view.using { it.toList() } shouldBe Either.Right(listOf<Byte>(3, 4, 5))
            ledger.drain()
            view.using { it.toList() } shouldBe Either.Right(listOf<Byte>(3, 4, 5))
            view.close()
            source.all { it == 0.toByte() } shouldBe true
        }
    }
}

private fun abandonedCopyWipes() {
    val source = byteArrayOf(3, 4, 5)
    var seen: ByteArray? = null
    val marker = IllegalStateException("synthetic receipt cleanup")
    withLoadGroups(listOf(source)) { ledger, flights, key, captured ->
        resourceScope {
            onRelease { _ -> seen?.fill(0) }
            shouldThrow<IllegalStateException>({
                failPreparedReceipt(ledger, marker, { seen = it }) {
                    ledger.deliver(key, captured, groupDeadline, flights) {
                        Either.Right(DataKey(KeyOwner.Platform, KeyPurpose.Enc, 1, source))
                    }
                }
            }) { (it === marker) shouldBe true }
            val copy = checkNotNull(seen)
            (copy !== source) shouldBe true
            copy.all { it == 0.toByte() } shouldBe true
            source.all { it == 0.toByte() } shouldBe true
        }
    }
}

private fun abandonedViewCloses() {
    val source = byteArrayOf(3, 4, 5)
    val marker = IllegalStateException("synthetic receipt cleanup")
    withLoadGroups(listOf(source)) { ledger, flights, legacy, captured ->
        val identity = KeyIdentity(KeyOwner.Platform, KeyPurpose.Enc, 1, byteArrayOf(1))
        shouldThrow<IllegalStateException>({
            failPreparedReceipt(ledger, marker) {
                ledger.deliver(legacy.copy(identity = identity), captured, groupDeadline, flights) {
                    Either.Right(DataKey(KeyOwner.Platform, KeyPurpose.Enc, 1, source))
                }
            }
        }) { (it === marker) shouldBe true }
        source.all { it == 0.toByte() } shouldBe true
    }
}

private fun <A> failPreparedReceipt(
    ledger: KeyCacheLedger,
    marker: Throwable,
    observeCopy: ((ByteArray) -> Unit)? = null,
    block: () -> A,
): A {
    val target = Thread.currentThread()
    var captured = false
    var fired = false
    val previous = metrics.get()
    val failing = object : Metrics by previous {
        override fun counter(name: String, tags: Map<String, String>): Counter = Counter { count ->
            previous.counter(name, tags).increment(count)
            val committed = name == "surefold.transact.runs" && tags["outcome"] == "committed"
            if (Thread.currentThread() === target && committed) {
                val shared = privateField(ledger, "state") as? Shared<*> ?: throw DeliveryProbeSetupFailure()
                val rows = privateField(shared.peek(), "prepared") as? List<*> ?: throw DeliveryProbeSetupFailure()
                if (rows.isNotEmpty()) {
                    if (!captured && observeCopy != null) {
                        observeCopy(snapshotPreparedCopy(rows))
                        captured = true
                    }
                    if (!fired && target.stackTrace.any { it.methodName == "finishReceipt" }) {
                        fired = true
                        throw marker
                    }
                }
            }
        }
    }
    return metrics.locally(failing, block)
}

private fun snapshotPreparedCopy(rows: List<*>): ByteArray {
    if (rows.size != 1) throw DeliveryProbeSetupFailure()
    val action = privateField(privateField(checkNotNull(rows.single()), "abort"), "action")
    val captures = action.javaClass.declaredFields.filter {
        it.type == ByteArray::class.java && !java.lang.reflect.Modifier.isStatic(it.modifiers)
    }
    if (captures.size != 1 || !captures.single().trySetAccessible()) throw DeliveryProbeSetupFailure()
    val bytes = captures.single().get(action) as ByteArray
    if (bytes.toList() != listOf<Byte>(3, 4, 5)) throw DeliveryProbeSetupFailure()
    return bytes
}

private fun privateField(owner: Any?, name: String): Any = try {
    if (owner == null) throw DeliveryProbeSetupFailure()
    val field = owner.javaClass.getDeclaredField(name)
    if (!field.trySetAccessible()) throw DeliveryProbeSetupFailure()
    field.get(owner) ?: throw DeliveryProbeSetupFailure()
} catch (_: ReflectiveOperationException) {
    throw DeliveryProbeSetupFailure()
}

private class DeliveryProbeSetupFailure : IllegalStateException("synthetic delivery probe setup failed")

@OptIn(TurnSeam::class)
private fun interruptedCloseAcknowledgement() = clock.locally(SystemClock) {
    timeout(10.seconds) {
        val ledger = KeyCacheLedger()
        val callers = KeyCallers(SingleFlight())
        val actions = Shared(0)
        val release = TestClock(Instant.EPOCH)
        val start = Promise<Nothing, Unit>()
        val holderReady = Promise<Nothing, Thread>()
        val interrupted = HandoffDecision<Unit>()
        scope {
            val blocker = async { release.sleep(1.seconds) }
            eventually(5.seconds) { release.sleepers() shouldBe 1 }
            val holder = async {
                holderReady.succeed(Thread.currentThread())
                start.awaitValue()
                transact(soloAfter = 0) { blocker.await() }
            }
            resourceScope {
                onRelease { _ -> release.adjust(1.seconds) }
                val holderThread = holderReady.awaitValue()
                ledger.owe {
                    actions.update { it + 1 }
                    start.succeed(Unit)
                    eventually(5.seconds) { (LockSupport.getBlocker(holderThread) === blocker) shouldBe true }
                }
                val closer = async {
                    takingTurns(InterruptCloseAck(Thread.currentThread(), interrupted)) {
                        callers.closeAndAwait(ledger)
                    }
                    Thread.interrupted()
                }
                eventually(5.seconds) { interrupted.peek() shouldBe Unit }
                release.adjust(1.seconds)
                holder.await()
                closer.await() shouldBe true
                ledger.drain()
                actions.peek() shouldBe 1
            }
        }
    }
}

@OptIn(TurnSeam::class)
private class InterruptCloseAck(private val closer: Thread, private val interrupted: HandoffDecision<Unit>) : Turns {
    override fun forking(): Any = this
    override fun started(ticket: Any) = Unit
    override fun ended() = Unit
    override fun woke() = Unit
    override fun waking(thread: Thread) = Unit

    override fun parking(): Boolean {
        if (Thread.currentThread() !== closer) return true
        val stack = closer.stackTrace
        val acknowledgement = stack.any { it.methodName == "acknowledge" }
        val committing = stack.any {
            it.className.endsWith(".Transaction") &&
                it.methodName.substringBefore('$') == "commit"
        }
        val held = stack.any { it.className.endsWith(".Solo") && it.methodName == "awaitFree" }
        if (!acknowledgement || !committing || !held) return true
        if (!interrupted.tryComplete(Unit)) return true
        closer.interrupt()
        return false
    }
}

private fun <A> revokePreparedView(ledger: KeyCacheLedger, block: () -> A): A =
    atDeliveryCommit({ stack -> stack.any { it.methodName == "registerPrepared" } }, {
        val state = privateField(ledger, "state") as Shared<*>
        val records = privateField(state.peek(), "records") as Map<*, *>
        val owner = privateField(records.values.single(), "source")
        (privateField(owner, "material") as OwnedKeyMaterial).revoke()
    }, block)

private fun preparingDrain(qualified: Boolean) {
    val source = byteArrayOf(3, 4, 5)
    withLoadGroups(listOf(source)) { ledger, flights, legacy, captured ->
        val key = deliveryKey(legacy, qualified)
        var drained = false
        val answer = atDeliveryCommit({ stack -> stack.any { it.methodName == "registerPrepared" } }, {
            ledger.drain()
            drained = true
        }) {
            ledger.deliver(key, captured, groupDeadline, flights) {
                Either.Right(DataKey(KeyOwner.Platform, KeyPurpose.Enc, 1, source))
            }
        }
        resourceScope {
            val delivery = (answer as Either.Right).value
            onRelease { _ -> discardDelivery(delivery) }
            drained shouldBe true
            deliveryBytes(delivery) shouldBe listOf<Byte>(3, 4, 5)
        }
    }
}

private fun deliveredReapFault(qualified: Boolean) {
    val source = byteArrayOf(3, 4, 5)
    val marker = IllegalStateException("synthetic delivered metadata")
    withLoadGroups(listOf(source)) { ledger, flights, legacy, captured ->
        val delivery = (
            ledger.deliver(deliveryKey(legacy, qualified), captured, groupDeadline, flights) {
                Either.Right(DataKey(KeyOwner.Platform, KeyPurpose.Enc, 1, source))
            } as Either.Right
            ).value
        resourceScope {
            onRelease { _ -> discardDelivery(delivery) }
            shouldThrow<IllegalStateException>({
                atDeliveryCommit({ stack -> stack.any { it.methodName == "drain" } }, { throw marker }) {
                    ledger.drain()
                }
            }) { (it === marker) shouldBe true }
            deliveryBytes(delivery) shouldBe listOf<Byte>(3, 4, 5)
            ledger.drain()
            deliveryBytes(delivery) shouldBe listOf<Byte>(3, 4, 5)
        }
    }
}

private fun registrationObserverFault(qualified: Boolean) {
    val source = byteArrayOf(3, 4, 5)
    val marker = IllegalStateException("synthetic prepared registration")
    withLoadGroups(listOf(source)) { ledger, flights, legacy, captured ->
        shouldThrow<IllegalStateException>({
            atDeliveryCommit({ stack -> stack.any { it.methodName == "registerPrepared" } }, { throw marker }) {
                ledger.deliver(deliveryKey(legacy, qualified), captured, groupDeadline, flights) {
                    Either.Right(DataKey(KeyOwner.Platform, KeyPurpose.Enc, 1, source))
                }
            }
        }) { (it === marker) shouldBe true }
        source.all { it == 0.toByte() } shouldBe true
        ledger.drain()
    }
}

private fun admissionObserverFault() {
    val source = byteArrayOf(3, 4, 5)
    val marker = IllegalStateException("synthetic view admission")
    withLoadGroups(listOf(source)) { ledger, flights, legacy, captured ->
        shouldThrow<IllegalStateException>({
            atDeliveryCommit({ stack ->
                stack.any { it.className.endsWith("OwnedKeyMaterial") && it.methodName == "borrow" } &&
                    stack.none { it.methodName == "registerPrepared" }
            }, { throw marker }) {
                ledger.deliver(deliveryKey(legacy, true), captured, groupDeadline, flights) {
                    Either.Right(DataKey(KeyOwner.Platform, KeyPurpose.Enc, 1, source))
                }
            }
        }) { (it === marker) shouldBe true }
        source.all { it == 0.toByte() } shouldBe true
        ledger.drain()
    }
}

private fun abortObserverFault(stage: String) {
    val source = byteArrayOf(3, 4, 5)
    val original = IllegalStateException("synthetic receipt cleanup")
    val cleanup = IllegalStateException("synthetic abort observer")
    withLoadGroups(listOf(source)) { ledger, flights, legacy, captured ->
        shouldThrow<IllegalStateException>({
            atDeliveryCommit({ stack ->
                val aborting = stack.any { it.methodName == "abortPrepared" }
                val acknowledging = stack.any { it.methodName == "acknowledge" }
                val material = stack.any { it.className.endsWith("OwnedKeyMaterial") }
                when (stage) {
                    "transfer" -> aborting && !acknowledging && !material
                    "action" -> aborting && material
                    "ack" -> aborting && acknowledging
                    else -> throw DeliveryProbeSetupFailure()
                }
            }, { throw cleanup }) {
                failPreparedReceipt(ledger, original) {
                    ledger.deliver(deliveryKey(legacy, true), captured, groupDeadline, flights) {
                        Either.Right(DataKey(KeyOwner.Platform, KeyPurpose.Enc, 1, source))
                    }
                }
            }
        }) {
            (it === original) shouldBe true
            it.suppressed.toList() shouldBe listOf(cleanup)
        }
        source.all { it == 0.toByte() } shouldBe true
        ledger.drain()
        val unexpected = IllegalStateException("synthetic replay after acknowledgement")
        atDeliveryCommit({ true }, { throw unexpected }) { ledger.drain() }
    }
}

private fun stalePreparedCleanup() {
    val oldSource = byteArrayOf(3, 4, 5)
    val newSource = byteArrayOf(6, 7, 8)
    val marker = IllegalStateException("synthetic stale metadata cleanup")
    withLoadGroups(listOf(oldSource, newSource)) { ledger, flights, key, captured ->
        val old = (
            ledger.deliver(key, captured, groupDeadline, flights) {
                Either.Right(DataKey(KeyOwner.Platform, KeyPurpose.Enc, 1, oldSource))
            } as Either.Right
            ).value
        var newer: KeyCacheLedger.Delivery? = null
        resourceScope {
            onRelease { _ -> discardDelivery(old) }
            onRelease { _ -> newer?.let { discardDelivery(it) } }
            shouldThrow<IllegalStateException>({
                atDeliveryCommit({ stack -> stack.any { it.methodName == "drain" } }, {
                    newer = (
                        ledger.deliver(key, captured, groupDeadline, flights) {
                            Either.Right(DataKey(KeyOwner.Platform, KeyPurpose.Enc, 1, newSource))
                        } as Either.Right
                        ).value
                    throw marker
                }) { ledger.drain() }
            }) { (it === marker) shouldBe true }
            ledger.drain()
            deliveryBytes(old) shouldBe listOf<Byte>(3, 4, 5)
            deliveryBytes(checkNotNull(newer)) shouldBe listOf<Byte>(6, 7, 8)
        }
    }
}

private fun leftIsNotDelivery() {
    val refusal = KeyError.Denied("synthetic refused key")
    withLoadGroups { ledger, flights, key, captured ->
        ledger.deliver(key, captured, groupDeadline, flights) { Either.Left(refusal) } shouldBe Either.Left(refusal)
        ledger.drain()
    }
}

private fun cancellationAbortsCopy() {
    val source = byteArrayOf(3, 4, 5)
    var seen: ByteArray? = null
    val original = CancellationException("synthetic receipt cancellation")
    withLoadGroups(listOf(source)) { ledger, flights, key, captured ->
        resourceScope {
            onRelease { _ -> seen?.fill(0) }
            shouldThrow<CancellationException>({
                failPreparedReceipt(ledger, original, { seen = it }) {
                    ledger.deliver(key, captured, groupDeadline, flights) {
                        Either.Right(DataKey(KeyOwner.Platform, KeyPurpose.Enc, 1, source))
                    }
                }
            }) { (it === original) shouldBe true }
            checkNotNull(seen).all { it == 0.toByte() } shouldBe true
        }
    }
}

private fun deliveryKey(key: KeyCacheLedger.LoadKey, qualified: Boolean): KeyCacheLedger.LoadKey =
    key.copy(identity = if (qualified) KeyIdentity(KeyOwner.Platform, KeyPurpose.Enc, 1, byteArrayOf(1)) else null)

private fun deliveryBytes(delivery: KeyCacheLedger.Delivery): List<Byte> = when (delivery) {
    is KeyCacheLedger.Delivery.Copy -> delivery.key.material.toList()
    is KeyCacheLedger.Delivery.View -> (delivery.view.using { it.toList() } as Either.Right).value
}

private fun discardDelivery(delivery: KeyCacheLedger.Delivery) = when (delivery) {
    is KeyCacheLedger.Delivery.Copy -> delivery.key.material.fill(0)
    is KeyCacheLedger.Delivery.View -> delivery.view.close()
}

private fun <A> atDeliveryCommit(
    matches: (Array<StackTraceElement>) -> Boolean,
    action: () -> Unit,
    block: () -> A,
): A {
    val target = Thread.currentThread()
    var fired = false
    val previous = metrics.get()
    val observer = object : Metrics by previous {
        override fun counter(name: String, tags: Map<String, String>): Counter = Counter { count ->
            previous.counter(name, tags).increment(count)
            val committed = name == "surefold.transact.runs" && tags["outcome"] == "committed"
            val targetCommit = Thread.currentThread() === target && committed
            if (!fired && targetCommit && matches(target.stackTrace)) {
                fired = true
                action()
            }
        }
    }
    return metrics.locally(observer, block)
}

private fun bothAbortPrecommitFailures() {
    val source = byteArrayOf(3, 4, 5)
    val original = IllegalStateException("synthetic committed group cleanup")
    withLoadGroups(listOf(source)) { ledger, flights, legacy, captured ->
        val (thrown, interruptions) = heldSoloDeliveryFault(false, original) {
            ledger.deliver(deliveryKey(legacy, true), captured, groupDeadline, flights) {
                Either.Right(DataKey(KeyOwner.Platform, KeyPurpose.Enc, 1, source))
            }
        }
        (thrown === original) shouldBe true
        interruptions shouldBe 2
        val transfer = thrown.suppressed.single()
        val close = transfer.suppressed.single()
        (transfer is InterruptedException) shouldBe true
        (close is InterruptedException) shouldBe true
        (transfer !== close) shouldBe true
        transfer.stackTrace.any { it.methodName == "awaitFree" } shouldBe true
        close.stackTrace.any { it.methodName == "awaitFree" } shouldBe true
        source.any { it != 0.toByte() } shouldBe true
        val attempts = Shared(0)
        val later = Shared(0)
        val cleanup = IllegalStateException("synthetic other pending action")
        ledger.owe {
            if (attempts.modify { it + 1 to it + 1 } == 1) {
                ledger.owe { later.update { it + 1 } }
                throw cleanup
            }
        }
        shouldThrow<IllegalStateException>({ ledger.drain() }) { (it === cleanup) shouldBe true }
        source.all { it == 0.toByte() } shouldBe true
        attempts.peek() shouldBe 1
        later.peek() shouldBe 0
        ledger.drain()
        attempts.peek() shouldBe 2
        later.peek() shouldBe 1
        ledger.drain()
    }
}

private fun registrationPrecommitFault(qualified: Boolean) {
    val source = byteArrayOf(3, 4, 5)
    val marker = IllegalStateException("synthetic registration gate")
    withLoadGroups(listOf(source)) { ledger, flights, legacy, captured ->
        val (thrown, interruptions) = heldSoloDeliveryFault(true, marker) {
            ledger.deliver(deliveryKey(legacy, qualified), captured, groupDeadline, flights) {
                Either.Right(DataKey(KeyOwner.Platform, KeyPurpose.Enc, 1, source))
            }
        }
        (thrown is InterruptedException) shouldBe true
        thrown.stackTrace.any { it.methodName == "awaitFree" } shouldBe true
        thrown.stackTrace.any { it.methodName == "registerPrepared" } shouldBe true
        interruptions shouldBe 1
        source.all { it == 0.toByte() } shouldBe true
        ledger.drain()
    }
}

private fun heldSoloDeliveryFault(
    registration: Boolean,
    original: IllegalStateException,
    invoke: () -> Unit,
): Pair<Exception, Int> {
    val fixture = SoloDeliveryFixture(registration, original)
    val blockerClock = TestClock(Instant.EPOCH)
    val startClock = TestClock(Instant.EPOCH)
    val holderReady = Promise<Nothing, Thread>()
    return clock.locally(SystemClock) {
        timeout(10.seconds) {
            scope {
                val blocker = async { blockerClock.sleep(1.seconds) }
                val holder = async {
                    holderReady.succeed(Thread.currentThread())
                    startClock.sleep(1.seconds)
                    try {
                        transact(soloAfter = 0) { blocker.await() }
                    } catch (_: InterruptedException) {
                        Unit
                    }
                }
                resourceScope {
                    onRelease { _ -> blocker.cancel() }
                    val holderThread = holderReady.awaitValue()
                    eventually(5.seconds) { blockerClock.sleepers() shouldBe 1 }
                    eventually(5.seconds) { startClock.sleepers() shouldBe 1 }
                    val target = async { fixture.run(invoke) }
                    fixture.prepared.awaitValue()
                    eventually(5.seconds) { fixture.pause.sleepers() shouldBe 1 }
                    startClock.adjust(1.seconds)
                    eventually(5.seconds) { (LockSupport.getBlocker(holderThread) === blocker) shouldBe true }
                    fixture.pause.adjustWhenBlocked(1.seconds)
                    val thrown = if (registration) {
                        eventually(5.seconds) { fixture.interruptGate.sleepers() shouldBe 1 }
                        blockerClock.adjust(1.seconds)
                        holder.await()
                        fixture.interruptGate.adjust(1.seconds)
                        target.await()
                    } else {
                        val failure = target.await()
                        blockerClock.adjust(1.seconds)
                        holder.await()
                        failure
                    }
                    thrown to fixture.interruptions
                }
            }
        }
    }
}

@OptIn(TurnSeam::class)
private class SoloDeliveryFixture(private val registration: Boolean, private val original: IllegalStateException) {
    val prepared = Promise<Nothing, Unit>()
    val pause = TestClock(Instant.EPOCH)
    val interruptGate = TestClock(Instant.EPOCH)
    var interruptions = 0
        private set

    fun run(invoke: () -> Unit): Exception {
        val current = Thread.currentThread()
        try {
            takingTurns(turns(current)) {
                atDeliveryCommit({ stack ->
                    if (registration) stack.any { it.className.endsWith(".Promise") && it.methodName == "await" }
                    else stack.any { it.methodName == "releaseGroup" } &&
                        stack.none { it.className.endsWith("OwnedKeyMaterial") }
                }, ::pauseAtPrepared, invoke)
            }
            throw DeliveryProbeSetupFailure()
        } catch (failure: InterruptedException) {
            if (!registration) throw failure
            return failure
        } catch (failure: IllegalStateException) {
            if (registration || failure !== original) throw failure
            return failure
        }
    }

    private fun pauseAtPrepared() {
        prepared.succeed(Unit)
        pause.sleep(1.seconds)
        if (!registration) throw original
    }

    private fun turns(current: Thread): Turns = object : Turns {
        private var insideGate = false
        override fun forking(): Any = this
        override fun started(ticket: Any) = Unit
        override fun ended() = Unit
        override fun woke() = Unit
        override fun waking(thread: Thread) = Unit
        override fun parking(): Boolean {
            if (Thread.currentThread() !== current || insideGate) return true
            val stack = current.stackTrace
            if (stack.none { it.className.endsWith(".Solo") && it.methodName == "awaitFree" }) return true
            val wanted = if (registration) "registerPrepared" else "abortPrepared"
            if (interruptions >= (if (registration) 1 else 2) || stack.none { it.methodName == wanted }) {
                throw DeliveryProbeSetupFailure()
            }
            interruptions++
            if (registration) {
                insideGate = true
                try {
                    interruptGate.sleep(1.seconds)
                } finally {
                    insideGate = false
                }
            }
            current.interrupt()
            return false
        }
    }
}

private fun deliveryRecursionRefuses() {
    val source = byteArrayOf(3, 4, 5)
    val nestedLoads = Shared(0)
    withLoadGroups(listOf(source)) { ledger, flights, key, captured ->
        clock.locally(SystemClock) {
            timeout(2.seconds) {
                val answer = ledger.deliver(key, captured, groupDeadline, flights) {
                    shouldThrow<IllegalStateException>({
                        ledger.deliver(key, captured, groupDeadline, flights) {
                            nestedLoads.update { it + 1 }
                            Either.Left(KeyError.Unavailable("synthetic recursive supplier"))
                        }
                    }) { it.message shouldBe "recursive key load" }
                    Either.Right(DataKey(KeyOwner.Platform, KeyPurpose.Enc, 1, source))
                }
                val delivery = (answer as Either.Right).value
                resourceScope {
                    onRelease { _ -> discardDelivery(delivery) }
                    nestedLoads.peek() shouldBe 0
                    source.all { it == 0.toByte() } shouldBe true
                    deliveryBytes(delivery) shouldBe listOf<Byte>(3, 4, 5)
                }
            }
        }
    }
}

private fun callerFailure(primary: Throwable, expectedExit: ExitCase) {
    val callers = KeyCallers(SingleFlight())
    val first = IllegalStateException("synthetic outer release")
    val second = IllegalStateException("synthetic inner release")
    val released = Shared(emptyList<Pair<Int, ExitCase>>())
    shouldThrow<Throwable>({
        callers.call {
            resourceScope {
                onRelease { exit ->
                    released.update { it + (1 to exit) }
                    throw first
                }
                onRelease { exit ->
                    released.update { it + (2 to exit) }
                    throw second
                }
                throw primary
            }
        }
    }) {
        (it === primary) shouldBe true
        it.suppressed.toList() shouldBe listOf(second, first)
    }
    released.peek() shouldBe listOf(2 to expectedExit, 1 to expectedExit)
}

private fun callerGuard(mode: String) {
    val callers = KeyCallers(SingleFlight())
    val body = HandoffDecision<Unit>()
    val commits = Shared(0)
    val invoke = {
        atDeliveryCommit({ stack -> stack.any { it.methodName == "admit" } }, {
            commits.update { it + 1 }
        }) {
            shouldThrow<IllegalStateException>({ callers.call { body.tryComplete(Unit) } }) {
                it.message shouldBe "the operation cannot run inside a transaction"
            }
        }
    }
    when (mode) {
        "ordinary" -> transact { invoke() }
        "nested" -> transact { transact { invoke() } }
        "solo" -> transact(soloAfter = 0) { invoke() }
        else -> throw DeliveryProbeSetupFailure()
    }
    body.peek() shouldBe null
    commits.peek() shouldBe 0
    callers.call { body.tryComplete(Unit) }
    body.peek() shouldBe Unit
}

private fun withCallerFenceContention(
    probe: CallerFenceProbe,
    whileBlocked: () -> Unit,
    interruptAfterPause: Boolean = true,
    afterHolder: ResourceScope.() -> Unit = {},
    afterResume: () -> Unit = {},
    invoke: () -> Unit,
) {
    clock.locally(SystemClock) {
        timeout(10.seconds) {
            scope {
                val blocker = async { probe.blocker.sleep(1.seconds) }
                resourceScope {
                    onRelease { _ -> blocker.cancel() }
                    val holder = async {
                        probe.holder.succeed(Thread.currentThread())
                        probe.holderStart.sleep(1.seconds)
                        transact(soloAfter = 0) { blocker.await() }
                    }
                    val target = async {
                        probe.target.succeed(Thread.currentThread())
                        probe.targetStart.sleep(1.seconds)
                        probe.run(interruptAfterPause, invoke)
                    }
                    val holderThread = probe.holder.awaitValue()
                    val targetThread = probe.target.awaitValue()
                    eventually(5.seconds) { probe.blocker.sleepers() shouldBe 1 }
                    eventually(5.seconds) { probe.holderStart.sleepers() shouldBe 1 }
                    eventually(5.seconds) { probe.targetStart.sleepers() shouldBe 1 }
                    probe.holderStart.adjust(1.seconds)
                    eventually(5.seconds) { (LockSupport.getBlocker(holderThread) === blocker) shouldBe true }
                    probe.targetStart.adjust(1.seconds)
                    eventually(5.seconds) { probe.pause.sleepers() shouldBe 1 }
                    (probe.waiting.peek() === targetThread) shouldBe true
                    whileBlocked()
                    probe.blocker.adjust(1.seconds)
                    holder.await()
                    afterHolder()
                    probe.pause.adjust(1.seconds)
                    afterResume()
                    target.await()
                }
            }
        }
    }
}

@OptIn(TurnSeam::class)
private class CallerFenceProbe {
    val holder = Promise<Nothing, Thread>()
    val target = Promise<Nothing, Thread>()
    val blocker = TestClock(Instant.EPOCH)
    val holderStart = TestClock(Instant.EPOCH)
    val targetStart = TestClock(Instant.EPOCH)
    val pause = TestClock(Instant.EPOCH)
    val waiting = HandoffDecision<Thread>()

    fun run(interruptAfterPause: Boolean = true, invoke: () -> Unit) {
        val current = Thread.currentThread()
        val turns = object : Turns {
            override fun forking(): Any = this
            override fun started(ticket: Any) = Unit
            override fun ended() = Unit
            override fun woke() = Unit
            override fun waking(thread: Thread) = Unit
            override fun parking(): Boolean {
                if (Thread.currentThread() !== current) return true
                val stack = current.stackTrace
                val nearest = stack.firstOrNull {
                    (it.className == "dev.surefold.Solo" && it.methodName == "awaitFree") ||
                        (it.className == "dev.surefold.TestClock" && it.methodName == "awaitUntil")
                }
                if (nearest?.className != "dev.surefold.Solo") return true
                if (stack.none {
                        it.className == "dev.surefold.Transaction" && it.methodName == "commit\$dev_surefold_surefold"
                    } ||
                    !waiting.tryComplete(current)
                ) throw CallerFenceProbeSetupFailure()
                // The nested clock park passes through this seam without selecting the outer Solo frame again.
                pause.sleep(1.seconds)
                if (interruptAfterPause) current.interrupt()
                return false
            }
        }
        takingTurns(turns, invoke)
    }
}

private class CallerFenceProbeSetupFailure : IllegalStateException("synthetic caller fence probe setup failed")

private class PendingCallerProbe {
    val gate = TestClock(Instant.EPOCH)
    val caller = Promise<Nothing, Thread>()
    val pool = TestClock(Instant.EPOCH)
    val closer = Promise<Nothing, Thread>()
    val ended = HandoffDecision<Boolean>()

    fun call(callers: KeyCallers, unwind: Boolean, afterHold: () -> Unit) = callers.call {
        caller.succeed(Thread.currentThread())
        val hold = {
            gate.sleep(1.seconds)
            afterHold()
        }
        resourceScope {
            onRelease { _ -> if (unwind) hold() }
            if (!unwind) hold()
        }
    }

    fun awaitHeld(): Thread {
        val thread = caller.awaitValue()
        eventually(5.seconds) { gate.sleepers() shouldBe 1 }
        return thread
    }

    fun finish(work: Deferred<*>) {
        gate.adjust(1.seconds)
        work.await()
        caller.awaitValue().isAlive shouldBe false
        gate.sleepers() shouldBe 0
    }
}

private fun pendingCaller(
    probe: PendingCallerProbe,
    unwind: Boolean = false,
    flights: SingleFlight<Any, KeyError, Unit> = SingleFlight(),
    workerFailure: HandoffDecision<Throwable>? = null,
    beforeWait: (Thread) -> Unit = {},
    inspect: (KeyCallers, Thread) -> Unit = { _, _ -> },
    invoke: (KeyCallers) -> Unit = { it.closeAndAwait() },
): KeyCallers = clock.locally(SystemClock) {
    timeout(10.seconds) {
        scope {
            val callers = KeyCallers(flights)
            resourceScope {
                onRelease { _ -> flights.closeAndJoin() }
                onRelease { _ -> probe.pool.adjust(1.seconds) }
                onRelease { _ -> probe.gate.adjust(1.seconds) }
                val caller = async {
                    probe.call(callers, unwind) {
                        shouldThrow<IllegalStateException>({ callers.closeAndAwait() }) {
                            it.message shouldBe "a key cache caller cannot close its owner"
                        }
                    }
                }
                probe.awaitHeld()
                val close = {
                    probe.closer.succeed(Thread.currentThread())
                    try {
                        clock.locally(fixedClock()) { invoke(callers) }
                    } finally {
                        probe.ended.tryComplete(Thread.currentThread().isInterrupted)
                    }
                }
                val closer = async {
                    if (workerFailure == null) close()
                    else shouldThrow<IllegalStateException>({
                        flights.run(Any()) {
                            close()
                            Either.Right(Unit)
                        }
                    }) { (it === workerFailure.peek()) shouldBe true }
                }
                val closeThread = probe.closer.awaitValue()
                beforeWait(closeThread)
                eventually(5.seconds) { closeThread.state shouldBe Thread.State.TIMED_WAITING }
                (LockSupport.getBlocker(closeThread) != null) shouldBe true
                closeThread.stackTrace.any { it.methodName == "closeAndAwait" } shouldBe true
                probe.ended.peek() shouldBe null
                val refused = HandoffDecision<Unit>()
                shouldThrow<IllegalStateException>({ callers.call { refused.tryComplete(Unit) } }) {
                    it.message shouldBe "the key cache is closed"
                }
                refused.peek() shouldBe null
                inspect(callers, closeThread)
                probe.finish(caller)
                closer.await()
                closeThread.isAlive shouldBe false
                callers
            }
        }
    }
}
