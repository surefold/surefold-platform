package dev.surefold.bank

import dev.surefold.actor.InMemoryJournal
import dev.surefold.actor.remote.Node
import dev.surefold.actor.remote.freePort
import dev.surefold.cluster.Discovery
import dev.surefold.fail.right
import dev.surefold.junit.Suite
import dev.surefold.junit.forAll
import dev.surefold.junit.shouldBe
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** One node on a journal in memory, and every transfer it hears end. */
private fun <T> bank(body: (BankNode, LinkedBlockingQueue<Pair<String, Phase>>) -> T): T {
    val ended = LinkedBlockingQueue<Pair<String, Phase>>()
    val port = freePort()
    val seeds = Discovery { listOf(Node("", "127.0.0.1", port)) }
    val node = BankNode("n1", port, seeds, InMemoryJournal(), calm) { id, end -> ended.put(id to end.phase) }
    return node.use {
        check(node.members.await(1.minutes) { seen -> seen.up.size == 1 }) { "n1 never saw itself up" }
        body(node, ended)
    }
}

private fun LinkedBlockingQueue<Pair<String, Phase>>.next() = poll(30, TimeUnit.SECONDS)

object SagaSuite : Suite() {
    init {
        test("a transfer debits one account, credits the other and ends done") {
            bank { node, ended ->
                node.open("a", 100) shouldBe 100L.right()
                node.open("b", 5) shouldBe 5L.right()

                node.transfer("t-1", "a", "b", 30) shouldBe Unit.right()

                ended.next() shouldBe ("t-1" to Phase.Done)
                node.status("t-1", within = 10.seconds) shouldBe Phase.Done
                node.statement("a").balance shouldBe 70L
                node.statement("b").balance shouldBe 35L
            }
        }

        test("a debit below zero is refused and moves nothing") {
            bank { node, ended ->
                node.open("a", 10)
                node.open("b", 0)

                node.transfer("t-1", "a", "b", 11) shouldBe Unit.right()

                ended.next() shouldBe ("t-1" to Phase.Refused)
                node.status("t-1", within = 10.seconds) shouldBe Phase.Refused
                node.statement("a") shouldBe Statement(open = true, balance = 10, movements = emptyList())
                node.statement("b").balance shouldBe 0L
            }
        }

        test("a transfer from an account never opened is refused") {
            bank { node, ended ->
                node.open("b", 0)

                node.transfer("t-1", "nobody", "b", 1) shouldBe Unit.right()

                ended.next() shouldBe ("t-1" to Phase.Refused)
                node.statement("b").balance shouldBe 0L
            }
        }

        test("an account opened twice keeps its first opening, and a transfer started twice runs once") {
            bank { node, ended ->
                node.open("a", 100) shouldBe 100L.right()
                node.open("a", 900) shouldBe 100L.right()
                node.open("b", 0)

                repeat(2) { node.transfer("t-1", "a", "b", 40) shouldBe Unit.right() }

                ended.next() shouldBe ("t-1" to Phase.Done)
                node.status("t-1", within = 10.seconds) shouldBe Phase.Done
                node.statement("a").balance shouldBe 60L
                node.statement("b").movements shouldBe listOf(Movement("t-1", 40))
            }
        }

        test("a transfer nobody started has no status") {
            bank { node, _ -> node.status("t-none") shouldBe null }
        }

        test("a statement shows the fifty latest movements, newest first") {
            bank { node, ended ->
                node.open("a", 2_000)
                node.open("b", 0)

                (1..55).forEach { node.transfer("t-$it", "a", "b", it.toLong()) shouldBe Unit.right() }
                repeat(55) { ended.next()?.second shouldBe Phase.Done }

                val statement = node.statement("a")
                statement.balance shouldBe 2_000L - (1..55).sum()
                statement.movements.toSet() shouldBe (55 downTo 6).map { Movement("t-$it", -it.toLong()) }.toSet()
            }
        }

        test("every move and every account event crosses the journal as it was") {
            val moves = listOf(Move("a", "b", 3), Move("x", "y", 9, at = 1_700_000_000_000))
            val events = listOf(AccountEvent.Opened(1), AccountEvent.Debited("t", 2), AccountEvent.Credited("t", 3))
            moves.forAll { MoveCodec.decode(MoveCodec.encode(it)) shouldBe it }
            events.forAll { AccountEvents.decode(AccountEvents.encode(it)) shouldBe it }
            Refusal.entries.forAll { Refusals.decode(Refusals.encode(it)) shouldBe it }
        }
    }
}
