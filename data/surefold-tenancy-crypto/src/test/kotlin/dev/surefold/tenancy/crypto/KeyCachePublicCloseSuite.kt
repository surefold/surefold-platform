package dev.surefold.tenancy.crypto

import dev.surefold.Counter
import dev.surefold.HandoffDecision
import dev.surefold.Metrics
import dev.surefold.Promise
import dev.surefold.Shared
import dev.surefold.SystemClock
import dev.surefold.awaitValue
import dev.surefold.clock
import dev.surefold.fail.Either
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldThrow
import dev.surefold.metrics
import dev.surefold.resourceScope
import dev.surefold.scope
import dev.surefold.test.eventually
import dev.surefold.timeout
import dev.surefold.transact
import java.util.concurrent.locks.LockSupport
import kotlin.time.Duration.Companion.seconds

object KeyCachePublicCloseSuite : Suite() {
    private val owner = KeyOwner.Platform
    private val identity = KeyIdentity(owner, KeyPurpose.Enc, 1, byteArrayOf(1))
    private val slot = KeyCache.Slot(owner, KeyPurpose.Enc, null)
    private val denied = Either.Left(KeyError.Denied("the key view is unavailable"))

    private fun load() = Either.Right(DataKey(owner, KeyPurpose.Enc, 1, byteArrayOf(7)))

    private fun lease(cache: KeyCache) = (cache.lease(identity, ::load) as Either.Right).value

    private fun <A> timed(body: () -> A): A = clock.locally(SystemClock) { timeout(10.seconds) { body() } }

    init {
        test("public close waits for an accepted use and revokes a forgotten idle view") {
            val cache = KeyCache()
            val held = lease(cache)
            val forgotten = lease(cache)
            cache.evict(owner)
            val copy = (cache.get(slot, ::load) as Either.Right).value
            val entered = Promise<Nothing, Unit>()
            val release = Promise<Nothing, Unit>()
            val first = CloseProbe()
            val second = CloseProbe()
            timed {
                scope {
                    onClose { release.succeed(Unit) }
                    val active = async {
                        held.using {
                            entered.succeed(Unit)
                            release.awaitValue()
                        }
                    }
                    entered.awaitValue()
                    val closing = async { first.close(cache) }
                    first.requireWaiting()
                    val concurrent = async { second.close(cache) }
                    second.requireWaiting()
                    forgotten.using { error("later use must refuse") } shouldBe denied

                    release.succeed(Unit)
                    active.await() shouldBe denied
                    closing.await() shouldBe Either.Right(Unit)
                    concurrent.await() shouldBe Either.Right(Unit)
                }
            }
            cache.closeAndJoin()
            cache.lease(identity) { error("later lease must refuse") } shouldBe denied
            shouldThrow<IllegalStateException>({ cache.get(slot) { error("later get must refuse") } }) {
                it.message shouldBe "the key cache is closed"
            }
            copy.material.toList() shouldBe listOf<Byte>(7)
            forgotten.close()
            held.close()
        }

        test("an accepted lease reaching enrolment after close fencing has no loader") {
            val cache = KeyCache()
            val admitted = Promise<Nothing, Unit>()
            val release = Promise<Nothing, Unit>()
            val probe = CloseProbe()
            timed {
                scope {
                    onClose { release.succeed(Unit) }
                    val waiting = async {
                        afterAdmission({ admitted.succeed(Unit); release.awaitValue() }) {
                            cache.lease(identity) { error("fenced receipt must refuse") }
                        }
                    }
                    admitted.awaitValue()
                    val closing = async { probe.close(cache) }
                    probe.requireWaiting()

                    release.succeed(Unit)
                    waiting.await() shouldBe denied
                    closing.await() shouldBe Either.Right(Unit)
                }
            }
        }

        test("close joins the cache loader and restores the closer's interruption") {
            val cache = KeyCache()
            val entered = Promise<Nothing, Unit>()
            val release = Promise<Nothing, Unit>()
            val exited = HandoffDecision<Unit>()
            timed {
                scope {
                    onClose { release.succeed(Unit) }
                    val loading = async {
                        Either.catchOrThrow<InterruptedException, Either<KeyError, KeyView>> {
                            cache.lease(identity) {
                                resourceScope {
                                    onRelease { _ -> exited.tryComplete(Unit) }
                                    entered.succeed(Unit)
                                    release.awaitValue()
                                    load()
                                }
                            }
                        }
                    }
                    entered.awaitValue()
                    val closing = async {
                        Thread.currentThread().interrupt()
                        cache.closeAndJoin()
                        Thread.interrupted()
                    }

                    closing.await() shouldBe true
                    exited.peek() shouldBe Unit
                    (loading.await() is Either.Left) shouldBe true
                }
            }
        }

        test("public self-close refuses before fencing and preserves user exceptions") {
            val cache = KeyCache()
            val other = KeyCache()
            val failure = IllegalStateException("the key cache is closed")
            resourceScope {
                onRelease { _ -> cache.closeAndJoin() }
                shouldThrow<IllegalStateException>({ cache.lease(identity) { throw failure } }) {
                    (it === failure) shouldBe true
                }
                shouldThrow<IllegalStateException>({ cache.lease(identity) { cache.closeAndJoin(); load() } }) {
                    it.message shouldBe "a key cache caller cannot close its owner"
                }
                val view = lease(cache)
                onRelease { _ -> view.close() }
                shouldThrow<IllegalStateException>({ view.using { cache.closeAndJoin() } }) {
                    it.message shouldBe "a key cache caller cannot close its owner"
                }
                shouldThrow<IllegalStateException>({ view.using { throw failure } }) { (it === failure) shouldBe true }
                shouldThrow<IllegalStateException>({ transact { cache.closeAndJoin() } })

                view.using { other.closeAndJoin(); it.single() } shouldBe Either.Right(7.toByte())
            }
        }

        test("public close reports new cleanup outside its snapshot and a later close discharges it") {
            val cache = KeyCache()
            val field = KeyCache::class.java.getDeclaredField("ledger").apply { isAccessible = true }
            val ledger = field.get(cache) as KeyCacheLedger
            val later = Shared(0)
            ledger.owe { ledger.owe { later.update { it + 1 } } }
            shouldThrow<IllegalStateException>({ cache.closeAndJoin() }) {
                it.message shouldBe "the key cache cleanup is incomplete"
            }
            later.peek() shouldBe 0

            cache.closeAndJoin()
            later.peek() shouldBe 1
        }
    }

    private class CloseProbe {
        private val thread = Promise<Nothing, Thread>()
        private val finished = HandoffDecision<Unit>()

        fun close(cache: KeyCache): Either<IllegalStateException, Unit> {
            thread.succeed(Thread.currentThread())
            return Either.catchOrThrow<IllegalStateException, Unit> { cache.closeAndJoin() }
                .also { finished.tryComplete(Unit) }
        }

        fun requireWaiting() {
            val closer = thread.awaitValue()
            eventually(5.seconds) {
                val observing = closer.stackTrace.any { it.methodName == "observe" && "KeyCallers" in it.className }
                (finished.peek() != null || (observing && LockSupport.getBlocker(closer) != null)) shouldBe true
            }
            finished.peek() shouldBe null
        }
    }

    private fun <A> afterAdmission(pause: () -> Unit, body: () -> A): A {
        val previous = metrics.get()
        val thread = Thread.currentThread()
        val seen = HandoffDecision<Unit>()
        val observed = object : Metrics by previous {
            override fun counter(name: String, tags: Map<String, String>): Counter = Counter { count ->
                previous.counter(name, tags).increment(count)
                val commit = name == "surefold.transact.runs" && tags["outcome"] == "committed"
                if (commit && Thread.currentThread() === thread) {
                    val admission = thread.stackTrace.any { it.methodName.substringBefore('$') == "admit" }
                    if (admission && seen.tryComplete(Unit)) pause()
                }
            }
        }
        return metrics.locally(observed, body)
    }
}
