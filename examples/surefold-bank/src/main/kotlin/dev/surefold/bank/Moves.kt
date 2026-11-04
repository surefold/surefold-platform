package dev.surefold.bank

import dev.surefold.actor.StateCodec
import dev.surefold.cluster.Sharded
import dev.surefold.cluster.saga.Durable
import dev.surefold.cluster.saga.durableSaga
import dev.surefold.cluster.saga.entityStep

/** A transfer as it is started: [at] is when it was asked for, in epoch milliseconds, so its end can say how long. */
internal data class Move(val from: String, val to: String, val pence: Long, val at: Long = 0)

/** Where a transfer is, as the API answers it. */
internal enum class Phase(val ended: Boolean = false) {
    Requested,
    Done(ended = true),
    Refused(ended = true),
}

/** How a transfer ended, as the node announces it. */
internal data class Ended(val phase: Phase, val move: Move)

/**
 * The transfer saga (spec 0299): a debit, undone by a credit back should the saga go on to fail, then a credit, each
 * applied once by its keyed account under the step's key. A crash between them is resumed from the journal on
 * whichever node owns the transfer then. [ended] hears it end; a crash before its end is written repeats that, so it
 * can hear one transfer end more than once.
 */
internal fun transfers(
    accounts: Sharded<AccountMsg>,
    ended: (id: String, Ended) -> Unit,
): Durable<Move, Refusal, Long> = durableSaga("transfer", MoveCodec, RefusalCodec, PenceCodec) { move ->
    entityStep(
        "debit",
        accounts,
        move.from,
        refused = { reason -> reason.also { ended(id, Ended(Phase.Refused, move)) } },
        undo = { AccountCommand.Credit(id, move.pence) },
    ) { AccountCommand.Debit(id, move.pence) }
    entityStep("credit", accounts, move.to, refused = { it }) { AccountCommand.Credit(id, move.pence) }
    ended(id, Ended(Phase.Done, move))
    move.pence
}

internal object MoveCodec : StateCodec<Move> {
    override fun encode(state: Move): ByteArray = "${state.from}|${state.to}|${state.pence}|${state.at}".toByteArray()

    override fun decode(bytes: ByteArray): Move {
        val (from, to, pence, at) = String(bytes).split("|")
        return Move(from, to, pence.toLong(), at.toLong())
    }
}

private object RefusalCodec : StateCodec<Refusal> {
    override fun encode(state: Refusal): ByteArray = state.name.toByteArray()

    override fun decode(bytes: ByteArray): Refusal = Refusal.valueOf(String(bytes))
}

private object PenceCodec : StateCodec<Long> {
    override fun encode(state: Long): ByteArray = state.toString().toByteArray()

    override fun decode(bytes: ByteArray): Long = String(bytes).toLong()
}
