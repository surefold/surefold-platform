package io.github.matthewjones372.surefold.tenancy.crypto

import io.github.matthewjones372.surefold.Counter
import io.github.matthewjones372.surefold.Metrics
import io.github.matthewjones372.surefold.NoMetrics
import io.github.matthewjones372.surefold.Promise
import io.github.matthewjones372.surefold.Shared
import io.github.matthewjones372.surefold.SystemClock
import io.github.matthewjones372.surefold.awaitValue
import io.github.matthewjones372.surefold.clock
import io.github.matthewjones372.surefold.junit.Suite
import io.github.matthewjones372.surefold.junit.shouldBe
import io.github.matthewjones372.surefold.junit.shouldThrow
import io.github.matthewjones372.surefold.metrics
import io.github.matthewjones372.surefold.resourceScope
import io.github.matthewjones372.surefold.scope
import io.github.matthewjones372.surefold.timeout
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
