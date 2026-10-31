package io.github.matthewjones372.surefold.tenancy.crypto

import io.github.matthewjones372.surefold.Counter
import io.github.matthewjones372.surefold.Metrics
import io.github.matthewjones372.surefold.NoMetrics
import io.github.matthewjones372.surefold.Promise
import io.github.matthewjones372.surefold.Shared
import io.github.matthewjones372.surefold.SingleFlight
import io.github.matthewjones372.surefold.SystemClock
import io.github.matthewjones372.surefold.TestClock
import io.github.matthewjones372.surefold.awaitValue
import io.github.matthewjones372.surefold.clock
import io.github.matthewjones372.surefold.fail.Either
import io.github.matthewjones372.surefold.junit.Suite
import io.github.matthewjones372.surefold.junit.shouldBe
import io.github.matthewjones372.surefold.junit.shouldThrow
import io.github.matthewjones372.surefold.metrics
import io.github.matthewjones372.surefold.resourceScope
import io.github.matthewjones372.surefold.scope
import io.github.matthewjones372.surefold.timeout
import io.github.matthewjones372.surefold.use
import java.time.Instant
import java.util.concurrent.CancellationException
import kotlin.time.Duration.Companion.seconds

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
