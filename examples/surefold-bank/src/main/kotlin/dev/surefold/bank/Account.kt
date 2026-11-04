package dev.surefold.bank

import dev.surefold.actor.EventCodec
import dev.surefold.actor.Journal
import dev.surefold.actor.Keyed
import dev.surefold.actor.KeyedEvent
import dev.surefold.actor.PersistenceId
import dev.surefold.actor.keyed
import dev.surefold.actor.keyedEvents
import dev.surefold.actor.remote.MessageCodec
import dev.surefold.actor.remote.WireException
import dev.surefold.actor.remote.WireIn
import dev.surefold.actor.remote.WireOut
import dev.surefold.actor.remote.codec

/** What an account is told, each under a key it answers once (spec 0300): a transfer's step, or its opening. */
internal sealed interface AccountCommand {
    /** Opens the account with [pence] in it, unless it is open already. */
    data class Open(val pence: Long) : AccountCommand

    data class Debit(val transfer: String, val pence: Long) : AccountCommand

    data class Credit(val transfer: String, val pence: Long) : AccountCommand
}

/** Why an account says no to a debit. */
internal enum class Refusal { Overdrawn, NotOpen }

internal typealias AccountMsg = Keyed<AccountCommand, Refusal>

/** The key an account's opening is answered under: one per account, so a second opening changes nothing. */
internal const val OPENING_KEY = "open"

/** Money in or out of an account: [pence] is negative for a debit. */
internal data class Movement(val transfer: String, val pence: Long)

internal data class Statement(val open: Boolean, val balance: Long, val movements: List<Movement>)

internal sealed interface AccountEvent {
    data class Opened(val pence: Long) : AccountEvent

    data class Debited(val transfer: String, val pence: Long) : AccountEvent

    data class Credited(val transfer: String, val pence: Long) : AccountEvent
}

/** How many of an account's movements its statement shows, newest first. */
private const val MOVEMENTS = 50

private fun Statement.moved(transfer: String, pence: Long) =
    copy(balance = balance + pence, movements = (listOf(Movement(transfer, pence)) + movements).take(MOVEMENTS))

internal fun Statement.after(event: AccountEvent): Statement = when (event) {
    is AccountEvent.Opened -> copy(open = true, balance = balance + event.pence)
    is AccountEvent.Debited -> moved(event.transfer, -event.pence)
    is AccountEvent.Credited -> moved(event.transfer, event.pence)
}

private val empty = Statement(open = false, balance = 0, movements = emptyList())

internal fun accountOf(id: String) = PersistenceId("account", id)

/**
 * The account [id], keyed: each debit or credit is applied once under its transfer's step key, and a repeat of the key
 * is answered as the first was, which is what a saga's step asks again after a lost answer.
 */
internal fun account(id: String) = keyed<AccountCommand, AccountEvent, Statement, Refusal>(
    id = accountOf(id),
    empty = empty,
    codec = AccountEvents,
    refusals = Refusals,
    decide = { statement, command ->
        when (command) {
            is AccountCommand.Open -> if (statement.open) accept() else accept(AccountEvent.Opened(command.pence))

            is AccountCommand.Debit -> when {
                !statement.open -> refuse(Refusal.NotOpen)
                statement.balance < command.pence -> refuse(Refusal.Overdrawn)
                else -> accept(AccountEvent.Debited(command.transfer, command.pence))
            }

            is AccountCommand.Credit -> accept(AccountEvent.Credited(command.transfer, command.pence))
        }
    },
    event = Statement::after,
)

/**
 * The account [id]'s statement, folded from its own journal: the bank's read side, kept apart from the entity, which
 * answers keys and nothing else.
 */
internal fun Journal.statement(id: String): Statement = read(accountOf(id))
    .map { keyedEvents(AccountEvents, Refusals).decode(it.bytes) }
    .filterIsInstance<KeyedEvent.Decided<AccountEvent>>()
    .fold(empty) { statement, decided -> statement.after(decided.event) }

internal object AccountEvents : EventCodec<AccountEvent> {
    override fun encode(event: AccountEvent): ByteArray = when (event) {
        is AccountEvent.Opened -> "O|${event.pence}"
        is AccountEvent.Debited -> "D|${event.transfer}|${event.pence}"
        is AccountEvent.Credited -> "C|${event.transfer}|${event.pence}"
    }.toByteArray()

    override fun decode(bytes: ByteArray): AccountEvent {
        val fields = String(bytes).split("|")
        return when (fields[0]) {
            "O" -> AccountEvent.Opened(fields[1].toLong())
            "D" -> AccountEvent.Debited(fields[1], fields[2].toLong())
            "C" -> AccountEvent.Credited(fields[1], fields[2].toLong())
            else -> error("no account event is written '${fields[0]}'")
        }
    }
}

internal object Refusals : EventCodec<Refusal> {
    override fun encode(event: Refusal): ByteArray = event.name.toByteArray()

    override fun decode(bytes: ByteArray): Refusal = Refusal.valueOf(String(bytes))
}

private object CommandWire : MessageCodec<AccountCommand> {
    override fun write(message: AccountCommand, out: WireOut): Unit = when (message) {
        is AccountCommand.Open -> {
            out.int(OPEN)
            out.long(message.pence)
        }

        is AccountCommand.Debit -> {
            out.int(DEBIT)
            out.string(message.transfer)
            out.long(message.pence)
        }

        is AccountCommand.Credit -> {
            out.int(CREDIT)
            out.string(message.transfer)
            out.long(message.pence)
        }
    }

    override fun read(input: WireIn): AccountCommand = when (val tag = input.int()) {
        OPEN -> AccountCommand.Open(input.long())
        DEBIT -> AccountCommand.Debit(input.string(), input.long())
        CREDIT -> AccountCommand.Credit(input.string(), input.long())
        else -> throw WireException("no account command has the tag $tag")
    }

    private const val OPEN = 1
    private const val DEBIT = 2
    private const val CREDIT = 3
}

private object RefusalWire : MessageCodec<Refusal> {
    override fun write(message: Refusal, out: WireOut) = out.int(message.ordinal)

    override fun read(input: WireIn): Refusal = Refusal.entries[input.int()]
}

/** An account's messages on the wire: each command with its key and the reply its answer goes back to. */
internal val AccountCodec: MessageCodec<AccountMsg> = Keyed.codec(CommandWire, RefusalWire)
