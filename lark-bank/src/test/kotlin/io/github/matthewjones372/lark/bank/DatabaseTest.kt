package io.github.matthewjones372.lark.bank

import arrow.core.right
import io.github.matthewjones372.lark.actor.PersistenceId
import io.github.matthewjones372.lark.actor.events
import io.github.matthewjones372.lark.actor.journal.jdbc.JdbcJournal
import io.kotest.matchers.shouldBe
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.junit.jupiter.api.Test
import java.util.UUID

class DatabaseTest {

    private val opened = PersistenceId("account", "a-1")

    @Test
    fun `a Postgres gets the journal's tables once, and keeps what is written to it`() {
        EmbeddedPostgres.start().use { postgres ->
            val url = postgres.getJdbcUrl("postgres", "postgres")
            JdbcJournal(database(url)).append(opened, 0, listOf(AccountEvents.encode(AccountEvent.Opened(5))))

            JdbcJournal(database(url)).events(opened, AccountEvents) shouldBe listOf(AccountEvent.Opened(5))
        }
    }

    @Test
    fun `an H2 database named twice is the same one, with its tables made once`() {
        val name = "bank-${UUID.randomUUID()}"
        JdbcJournal(h2(name)).append(opened, 0, listOf(AccountEvents.encode(AccountEvent.Opened(7)))) shouldBe
            1L.right()

        JdbcJournal(h2(name)).events(opened, AccountEvents) shouldBe listOf(AccountEvent.Opened(7))
    }
}
