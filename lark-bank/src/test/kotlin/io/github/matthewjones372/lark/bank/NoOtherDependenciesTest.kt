package io.github.matthewjones372.lark.bank

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import org.junit.jupiter.api.Test
import java.io.File

/** What the bank runs on, stated as a test: an application may choose its dependencies, but it says which. */
class NoOtherDependenciesTest {

    // The journal on Postgres, with the one jar its driver brings, through HikariCP and the logging API it brings;
    // and Liquibase, which applies the journal's changelog, with the jars it brings.
    private val allowed = listOf(
        "kotlin-stdlib", "annotations-", "arrow-", "lark", "postgresql-", "checker-qual-", "HikariCP-", "slf4j-api-",
        "liquibase-core-", "opencsv-", "snakeyaml-", "jaxb-api-", "commons-collections4-", "commons-text-",
        "commons-lang3-", "commons-io-",
    )

    @Test
    fun `the main runtime classpath is lark-cluster, the JDBC journal, Postgres and Liquibase, and no more`() {
        val raw = System.getProperty("lark.bank.runtimeClasspath")
        withClue("the build must pass -Dlark.bank.runtimeClasspath; see lark-bank/build.gradle.kts") {
            raw.shouldNotBeNull()
        }

        val unexpected = raw.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("lark-bank must stay lark-cluster, the journal and what it runs on, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }
}
