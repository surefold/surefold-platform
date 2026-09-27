package io.github.matthewjones372.lark.bank

import arrow.core.right
import io.github.matthewjones372.lark.actor.ActorRef
import io.github.matthewjones372.lark.actor.Address
import io.github.matthewjones372.lark.actor.Confirmed
import io.github.matthewjones372.lark.actor.Delivery
import io.github.matthewjones372.lark.actor.PersistenceId
import io.github.matthewjones372.lark.actor.TestActor
import io.github.matthewjones372.lark.actor.TestActors
import io.github.matthewjones372.lark.actor.events
import io.github.matthewjones372.lark.actor.remote.MessageCodec
import io.github.matthewjones372.lark.actor.remote.outbox
import io.github.matthewjones372.lark.actor.testActors
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** Two accounts and the transfers between them, each sending straight to the other rather than through a producer. */
private class Bank(val actors: TestActors, private val answering: Boolean = true) {
    val answers = CopyOnWriteArrayList<Pair<String, TransferMsg>>()
    private val accounts = ConcurrentHashMap<String, TestActor<AccountMsg, *, *>>()
    private val transfers = ConcurrentHashMap<String, TestActor<TransferMsg, *, *>>()
    val ended = CopyOnWriteArrayList<Pair<String, Phase>>()

    fun account(id: String): TestActor<AccountMsg, *, *> = accounts.computeIfAbsent(id) {
        actors.spawn(
            "account-$id",
            account(id) { to, answer ->
                answers += to to answer
                if (answering) transfer(to).tell(answer)
            },
        )
    }

    fun transfer(id: String): TestActor<TransferMsg, *, *> = transfers.computeIfAbsent(id) {
        actors.spawn(
            "transfer-$id",
            transfer(id, { account, command -> account(account).tell(command) }) { transfer, saga ->
                ended +=
                    transfer to saga.phase
            },
        )
    }

    fun open(id: String, pence: Long) = account(id).ask<Long> { Open(pence, it) }

    fun balance(id: String) = account(id).ask<Long> { Balance(it) }

    fun status(transfer: String) = transfer(transfer).ask<String> { Status(it) }
}

private class Confirms : ActorRef<Confirmed> {
    val confirmed = CopyOnWriteArrayList<Confirmed>()
    override val address = Address("test", "/confirms", 1)

    override fun tell(message: Confirmed) {
        confirmed += message
    }
}

private fun <M : Any> roundTrip(codec: MessageCodec<M>, message: M): M = codec.outbox().let {
    it.decode(it.encode(message))
}

class SagaTest {

    @Test
    fun `a transfer debits one account, credits the other and ends done`() = testActors<Unit> {
        val bank = Bank(this)
        bank.open("a", 100)
        bank.open("b", 5)

        bank.transfer("t-1").tell(Start("a", "b", 30))

        bank.status("t-1") shouldBe "Done".right()
        bank.balance("a") shouldBe 70L.right()
        bank.balance("b") shouldBe 35L.right()
    }

    @Test
    fun `a debit below zero is refused and moves nothing`() = testActors<Unit> {
        val bank = Bank(this)
        bank.open("a", 10)
        bank.open("b", 0)

        bank.transfer("t-1").tell(Start("a", "b", 11))

        bank.status("t-1") shouldBe "Refused".right()
        bank.balance("a") shouldBe 10L.right()
        bank.balance("b") shouldBe 0L.right()
        journal.events(PersistenceId("account", "a"), AccountEvents).last() shouldBe AccountEvent.Refused("t-1", 11)
    }

    @Test
    fun `a transfer is heard to end once it has, and again each time an answer to it is repeated`() = testActors<Unit> {
        val bank = Bank(this, answering = false)
        bank.open("a", 50)
        bank.open("b", 0)
        val transfer = bank.transfer("t-1")
        transfer.tell(Start("a", "b", 50))
        transfer.tell(DebitDone(taken = true))
        bank.ended shouldBe emptyList()

        repeat(2) { transfer.tell(CreditDone) }

        bank.ended shouldBe List(2) { "t-1" to Phase.Done }
        bank.status("t-1") shouldBe "Done".right()
    }

    @Test
    fun `answers lost on the way are asked for again, and each step moves the money once`() = testActors<Unit> {
        val bank = Bank(this, answering = false)
        bank.open("a", 100)
        bank.open("b", 0)
        val transfer = bank.transfer("t-1")

        repeat(2) { transfer.tell(Start("a", "b", 40)) }
        repeat(2) { transfer.tell(DebitDone(taken = true)) }
        transfer.tell(CreditDone)
        transfer.tell(DebitDone(taken = true))

        bank.answers shouldBe List(2) { "t-1" to DebitDone(taken = true) } + List(2) { "t-1" to CreditDone }
        bank.balance("a") shouldBe 60L.right()
        bank.balance("b") shouldBe 40L.right()
        bank.status("t-1") shouldBe "Done".right()
        bank.ended shouldBe List(2) { "t-1" to Phase.Done }
    }

    @Test
    fun `a debit delivered twice is answered twice, confirmed twice and taken once`() = testActors<Unit> {
        val bank = Bank(this)
        val confirms = Confirms()
        bank.open("a", 100)
        val delivery = Delivery("saga-n1", "a", 1, confirms)

        repeat(2) { bank.account("a").tell(ToAccount(Debit("t-1", 25), delivery)) }

        bank.balance("a") shouldBe 75L.right()
        bank.answers.filter { it.first == "t-1" } shouldBe List(2) { "t-1" to DebitDone(taken = true) }
        confirms.confirmed shouldBe List(2) { Confirmed("a", 1) }
    }

    @Test
    fun `every message crosses the wire and every event the journal as it was`() {
        val delivery = Delivery("saga-n1", "a", 3, Delivery.NoOne)
        val accounts = listOf(Debit("t", 1), Credit("t", 2), ToAccount(Credit("t", 4), delivery))
        val transfers = listOf(Start("a", "b", 3), DebitDone(false), CreditDone, ToTransfer(CreditDone, delivery))
        accounts.forEach { roundTrip(AccountCodec, it) shouldBe it }
        transfers.forEach { roundTrip(TransferCodec, it) shouldBe it }
        val events = listOf(AccountEvent.Opened(1), AccountEvent.Debited("t", 2), AccountEvent.Credited("t", 3))
        events.forEach { AccountEvents.decode(AccountEvents.encode(it)) shouldBe it }
        val steps = listOf(TransferEvent.Requested("a", "b", 1), TransferEvent.Debited, TransferEvent.Credited)
        steps.forEach { TransferEvents.decode(TransferEvents.encode(it)) shouldBe it }
    }
}
