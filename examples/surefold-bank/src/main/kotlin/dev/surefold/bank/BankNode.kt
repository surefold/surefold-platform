package dev.surefold.bank

import dev.surefold.Promise
import dev.surefold.Schedule
import dev.surefold.actor.ActorRef
import dev.surefold.actor.AskFailure
import dev.surefold.actor.Journal
import dev.surefold.actor.KeyAnswer
import dev.surefold.actor.Keyed
import dev.surefold.actor.ask
import dev.surefold.actor.journal
import dev.surefold.actor.remote.node
import dev.surefold.actor.spawn
import dev.surefold.awaitValue
import dev.surefold.cluster.Cluster
import dev.surefold.cluster.Discovery
import dev.surefold.cluster.Downing
import dev.surefold.cluster.Gossiping
import dev.surefold.cluster.MemberEvent
import dev.surefold.cluster.Sharded
import dev.surefold.cluster.Status
import dev.surefold.cluster.cluster
import dev.surefold.cluster.saga.Durable
import dev.surefold.cluster.saga.Sagas
import dev.surefold.cluster.saga.on
import dev.surefold.cluster.sharding
import dev.surefold.cluster.topic
import dev.surefold.fail.Either
import dev.surefold.fail.attempt
import dev.surefold.fail.bind
import dev.surefold.increment
import dev.surefold.metrics
import dev.surefold.retrying
import dev.surefold.scope
import java.util.concurrent.TimeoutException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** How a node runs. The gossip is calm: brisk probes take a node busy with transfers for a node that has gone. */
internal data class Settings(
    val gossiping: Gossiping = Gossiping(probeEvery = 1.seconds, ackWithin = 500.milliseconds, formAfter = 2.seconds),
    val downAfter: Duration = 3.seconds,
    val passivateAfter: Duration = 2.minutes,
    val sampleEvery: Duration = 1.seconds,
    val loadEvery: Duration = 20.milliseconds,
)

private class Parts(
    val cluster: Cluster,
    val accounts: Sharded<AccountMsg>,
    val transfers: Sagas<Move, Refusal, Long>,
    val journal: Journal,
    val load: ActorRef<Load>,
)

/**
 * One node of the bank, on a thread of its own until [close]: a cluster member with the keyed accounts and the
 * transfer sagas sharded on it (specs 0299, 0300). A saga's steps are journalled, so when a node crashes the cluster
 * resumes its transfers elsewhere. [close] goes as a crash does: it leaves and drains nothing.
 */
internal class BankNode(
    val name: String,
    port: Int,
    seeds: Discovery,
    journal: Journal,
    settings: Settings = Settings(),
    ended: (String, Ended) -> Unit = { _, _ -> },
) : AutoCloseable {
    val members = Members()

    /** This node's browsers' streams, fed by its dashboard. */
    val hub = Hub()
    private val recorder = Recorder()
    private val done = Promise<Nothing, Unit>("bank.done")
    private val started = Promise<Nothing, Parts>("bank.started")
    private val thread = Thread.ofPlatform().name("bank-$name").start {
        metrics.locally(recorder) { run(port, seeds, journal, settings, ended) }
    }

    private fun run(port: Int, seeds: Discovery, journal: Journal, settings: Settings, ended: (String, Ended) -> Unit) {
        scope {
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
            val announce = { id: String, end: Ended ->
                val ms = System.currentTimeMillis() - end.move.at
                recorder.counter(ENDED, mapOf("outcome" to end.phase.name)).increment()
                recorder.histogram(TOOK, emptyMap()).record(ms.toDouble())
                val move = end.move
                events.publish(BankEvent.Ended(id, move.from, move.to, move.pence, end.phase.name, ms))
                ended(id, end)
            }
            val accounts = cluster.sharding("account", AccountCodec, settings.passivateAfter, entity = ::account)
            val transfers = transfers(accounts, announce).on(cluster, settings.passivateAfter)

            val watcher = members.subscriber { event, seen ->
                // One member tells the dashboards each change: the oldest that every other can reach.
                val teller = seen.up.firstOrNull { it.node !in seen.unreachable }?.node == cluster.self
                val status = event.status()
                if (teller && status != null) events.publish(BankEvent.Member(event.member.node.name, status))
            }
            cluster.subscribe(spawn("members", watcher))
            members.await(1.minutes) { seen -> seen.members[cluster.self]?.status == Status.Up }
            started.succeed(Parts(cluster, accounts, transfers, journal, load))
            done.awaitValue()
        }
    }

    private val parts: Parts
        get() = started.awaitValue(within = 1.minutes) ?: throw TimeoutException("bank-$name did not start")

    /** Opens [account] with [pence] unless it is open, and answers its balance. */
    fun open(account: String, pence: Long): Either<AskFailure, Long> = attempt {
        parts.accounts.entity(account)
            .ask<AccountMsg, KeyAnswer<Refusal>>(ASK) { Keyed.Apply(OPENING_KEY, AccountCommand.Open(pence), it) }
            .bind()
        statement(account).balance
    }

    /** [account]'s statement, folded from its journal. */
    fun statement(account: String): Statement = parts.journal.statement(account)

    /**
     * Starts the transfer [id], answering once its start is journalled: from then on no crash loses it. A start sent to
     * a member that has just gone is sent again until its shard is up elsewhere; a second start of one id runs nothing.
     */
    fun transfer(id: String, from: String, to: String, pence: Long): Either<AskFailure, Unit> {
        val move = Move(from, to, pence, System.currentTimeMillis())
        return (Schedule.spaced<AskFailure>(START_AGAIN_AFTER) and Schedule.upTo(START)).retrying {
            parts.transfers.start(id, move, within = ASK).bind()
        }
    }

    /**
     * Where the transfer [id] is, waiting up to [within] for it to end, so one ending as it is asked about is answered
     * as ended: null for one nobody started.
     */
    fun status(id: String, within: Duration = STATUS_WITHIN): Phase? = when (parts.transfers.await(id, within)) {
        is Durable.Outcome.Done -> Phase.Done
        is Durable.Outcome.Refused -> Phase.Refused
        Durable.Outcome.Running, is Durable.Outcome.Stuck -> Phase.Requested
        Durable.Outcome.Forgotten -> null
    }

    /** Starts or stops this node's load of random transfers. */
    fun load(on: Boolean) = parts.load.tell(if (on) Load.On else Load.Off)

    override fun close() {
        done.succeed(Unit)
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

        // A start sent as a node goes waits for its transfer's shard to move to a member still up.
        val START = 30.seconds
        val START_AGAIN_AFTER = 250.milliseconds

        // Long enough for an unknown id to be read as such, and for a transfer just ended to have its end written.
        val STATUS_WITHIN = 1.seconds
    }
}
