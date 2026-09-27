package io.github.matthewjones372.lark.bank

import arrow.core.Either
import io.github.matthewjones372.lark.actor.ActorRef
import io.github.matthewjones372.lark.actor.AskFailure
import io.github.matthewjones372.lark.actor.Journal
import io.github.matthewjones372.lark.actor.NotSent
import io.github.matthewjones372.lark.actor.Producer
import io.github.matthewjones372.lark.actor.ask
import io.github.matthewjones372.lark.actor.journal
import io.github.matthewjones372.lark.actor.remote.node
import io.github.matthewjones372.lark.actor.spawn
import io.github.matthewjones372.lark.cluster.Cluster
import io.github.matthewjones372.lark.cluster.Discovery
import io.github.matthewjones372.lark.cluster.Downing
import io.github.matthewjones372.lark.cluster.Gossiping
import io.github.matthewjones372.lark.cluster.Member
import io.github.matthewjones372.lark.cluster.MemberEvent
import io.github.matthewjones372.lark.cluster.Sharded
import io.github.matthewjones372.lark.cluster.Status
import io.github.matthewjones372.lark.cluster.cluster
import io.github.matthewjones372.lark.cluster.sharding
import io.github.matthewjones372.lark.cluster.topic
import io.github.matthewjones372.lark.flock
import io.github.matthewjones372.lark.increment
import io.github.matthewjones372.lark.metrics
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** How a node runs. The gossip is calm: brisk probes take a node busy with transfers for a node that has gone. */
internal data class Settings(
    val gossiping: Gossiping = Gossiping(probeEvery = 1.seconds, ackWithin = 500.milliseconds, formAfter = 2.seconds),
    val downAfter: Duration = 3.seconds,
    val resendAfter: Duration = 1.seconds,
    val passivateAfter: Duration = 2.minutes,
    val sampleEvery: Duration = 1.seconds,
    val loadEvery: Duration = 20.milliseconds,
)

/** Commands a producer may keep unconfirmed: enough that a load never waits on one. */
private const val KEEP = 100_000

/** One node's two durable producers: debits and credits to accounts, and starts and answers to transfers. */
private class Outbox(val accounts: Producer<AccountMsg>, val transfers: Producer<TransferMsg>) {
    fun toAccount(id: String, command: AccountMsg) =
        check(accounts.send(id) { ToAccount(command, it) }.isRight()) { "no room to send $command to $id" }

    fun toTransfer(id: String, command: TransferMsg) =
        check(transfers.send(id) { ToTransfer(command, it) }.isRight()) { "no room to send $command to $id" }
}

private class Parts(
    val cluster: Cluster,
    val accounts: Sharded<AccountMsg>,
    val transfers: Sharded<TransferMsg>,
    val outbox: Outbox,
    val load: ActorRef<Load>,
)

/**
 * One node of the bank, on a thread of its own until [close]: a cluster member with the accounts and transfers
 * sharded on it. Its producers are named by its incarnation, and when a member is removed, the oldest left starts
 * that member's producers again, to send what it had kept. [close] goes as a crash does: it leaves and drains nothing.
 */
internal class BankNode(
    val name: String,
    port: Int,
    seeds: Discovery,
    journal: Journal,
    settings: Settings = Settings(),
    ended: (String, Saga) -> Unit = { _, _ -> },
) : AutoCloseable {
    val members = Members()

    /** This node's browsers' streams, fed by its dashboard. */
    val hub = Hub()
    private val recorder = Recorder()
    private val done = CountDownLatch(1)

    // Set before the flock closes, so a member removed meanwhile is not adopted by a node on its way out.
    @Volatile
    private var closing = false
    private val started = CompletableFuture<Parts>()
    private val thread = Thread.ofPlatform().name("bank-$name").start {
        metrics.locally(recorder) { run(port, seeds, journal, settings, ended) }
    }

    private fun run(port: Int, seeds: Discovery, journal: Journal, settings: Settings, ended: (String, Saga) -> Unit) {
        flock<Nothing, Unit> {
            journal(journal)
            val downing = Downing.keepMajority(stableAfter = settings.downAfter)
            val cluster = cluster(node(name, port), seeds, settings.gossiping, downing, leaveWithin = Duration.ZERO)
            val stats = cluster.topic("bank-stats", NodeStatsCodec)
            val events = cluster.topic("bank-events", BankEventCodec)
            val dashboard = spawn("dashboard", dashboard(hub))
            stats.subscribe(dashboard)
            events.subscribe(dashboard)
            spawn("sampler", sampler(name, recorder, stats, settings.sampleEvery))
            val load = spawn("load", load(this@BankNode, settings.loadEvery))
            val announce = { id: String, saga: Saga ->
                val ms = System.currentTimeMillis() - saga.at
                recorder.counter(ENDED, mapOf("outcome" to saga.phase.name)).increment()
                recorder.histogram(TOOK, emptyMap()).record(ms.toDouble())
                events.publish(BankEvent.Ended(id, saga.from, saga.to, saga.pence, saga.phase.name, ms))
                ended(id, saga)
            }
            val outbox = CompletableFuture<Outbox>()
            val accounts = cluster.sharding("account", AccountCodec, settings.passivateAfter) { id ->
                account(id) { transfer, answer -> outbox.get().toTransfer(transfer, answer) }
            }
            val transfers = cluster.sharding("transfer", TransferCodec, settings.passivateAfter) { id ->
                transfer(id, { account, command -> outbox.get().toAccount(account, command) }, announce)
            }

            fun outboxOf(member: Member): Outbox {
                val id = "${member.node.name}-${member.uid.toULong().toString(radix = 36)}"
                return Outbox(
                    accounts.reliable(id, settings.resendAfter, KEEP, durable = true),
                    transfers.reliable(id, settings.resendAfter, KEEP, durable = true),
                )
            }
            val watcher = members.subscriber { event, seen ->
                val oldest = seen.up.firstOrNull()?.node == cluster.self
                if (event is MemberEvent.Removed && oldest && !closing) outboxOf(event.member)
                // One member tells the dashboards each change: the oldest that every other can reach.
                val teller = seen.up.firstOrNull { it.node !in seen.unreachable }?.node == cluster.self
                val status = event.status()
                if (teller && status != null) events.publish(BankEvent.Member(event.member.node.name, status))
            }
            cluster.subscribe(spawn("members", watcher))
            members.await(1.minutes) { seen -> seen.members[cluster.self]?.status == Status.Up }
            outbox.complete(outboxOf(members.seen.members.getValue(cluster.self)))
            started.complete(Parts(cluster, accounts, transfers, outbox.get(), load))
            done.await()
        }
    }

    private val parts: Parts get() = started.get(1, TimeUnit.MINUTES)

    fun open(account: String, pence: Long): Either<AskFailure, Long> =
        parts.accounts.entity(account).ask(ASK) { Open(pence, it) }

    fun statement(account: String): Either<AskFailure, Statement> =
        parts.accounts.entity(account).ask(ASK) { Balance(it) }

    /** Starts the transfer [id] once its start is kept in this node's journal: from then on no crash loses it. */
    fun transfer(id: String, from: String, to: String, pence: Long): Either<NotSent, Unit> {
        val at = System.currentTimeMillis()
        return parts.outbox.transfers.send(id) { ToTransfer(Start(from, to, pence, at), it) }
    }

    fun status(id: String): Either<AskFailure, String> = parts.transfers.entity(id).ask(ASK) { Status(it) }

    /** Starts or stops this node's load of random transfers. */
    fun load(on: Boolean) = parts.load.tell(if (on) Load.On else Load.Off)

    override fun close() {
        closing = true
        done.countDown()
        thread.join()
    }

    /** What the admin page is told; null for a member downed as it stops being unreachable, since `Downed` follows. */
    private fun MemberEvent.status(): String? = when (this) {
        is MemberEvent.Up -> "Up"
        is MemberEvent.Unreachable -> "Unreachable"
        is MemberEvent.Reachable -> "Reachable".takeIf { member.status != Status.Down }
        is MemberEvent.Downed -> "Downed"
        is MemberEvent.Removed -> "Removed"
    }

    private companion object {
        val ASK = 5.seconds
    }
}
