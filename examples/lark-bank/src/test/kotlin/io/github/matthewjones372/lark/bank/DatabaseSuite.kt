package io.github.matthewjones372.lark.bank

import io.github.matthewjones372.lark.actor.PersistenceId
import io.github.matthewjones372.lark.actor.events
import io.github.matthewjones372.lark.actor.journal.jdbc.JdbcJournal
import io.github.matthewjones372.lark.actor.journal.jdbc.Postgres
import io.github.matthewjones372.lark.junit.Suite
import io.github.matthewjones372.lark.junit.shouldBe

private val opened = PersistenceId("account", "a-1")

object DatabaseSuite : Suite() {
    init {
        test("a Postgres gets the journal's tables once, and keeps what is written to it") {
            val url = Postgres.emptyUrl()
            database(url).use { pool ->
                JdbcJournal(pool).append(opened, 0, listOf(AccountEvents.encode(AccountEvent.Opened(5))))
            }

            database(url).use { pool ->
                JdbcJournal(pool).events(opened, AccountEvents) shouldBe listOf(AccountEvent.Opened(5))
            }
        }
    }
}
