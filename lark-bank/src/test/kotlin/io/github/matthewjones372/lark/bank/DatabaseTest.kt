package io.github.matthewjones372.lark.bank

import io.github.matthewjones372.lark.actor.PersistenceId
import io.github.matthewjones372.lark.actor.events
import io.github.matthewjones372.lark.actor.journal.jdbc.JdbcJournal
import io.github.matthewjones372.lark.actor.journal.jdbc.Postgres
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class DatabaseTest {

    private val opened = PersistenceId("account", "a-1")

    @Test
    fun `a Postgres gets the journal's tables once, and keeps what is written to it`() {
        val url = Postgres.emptyUrl()
        JdbcJournal(database(url)).append(opened, 0, listOf(AccountEvents.encode(AccountEvent.Opened(5))))

        JdbcJournal(database(url)).events(opened, AccountEvents) shouldBe listOf(AccountEvent.Opened(5))
    }
}
