package io.github.matthewjones372.lark.bank

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import org.junit.jupiter.api.Test
import java.io.File

/** What the bank runs on, stated as a test: an application may choose its dependencies, but it says which. */
class NoOtherDependenciesTest {

    // The journal's two databases: H2 in memory, and Postgres with the one jar its driver brings.
    private val allowed =
        listOf("kotlin-stdlib", "annotations-", "arrow-", "lark", "h2-", "postgresql-", "checker-qual-")

    @Test
    fun `the main runtime classpath is lark-cluster, the JDBC journal and its two drivers, and nothing else`() {
        val raw = System.getProperty("lark.bank.runtimeClasspath")
        withClue("the build must pass -Dlark.bank.runtimeClasspath; see lark-bank/build.gradle.kts") {
            raw.shouldNotBeNull()
        }

        val unexpected = raw.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("lark-bank must stay lark-cluster, the journal and H2 and Postgres, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }
}
