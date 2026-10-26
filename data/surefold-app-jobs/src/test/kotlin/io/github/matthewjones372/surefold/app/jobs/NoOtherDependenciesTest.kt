package io.github.matthewjones372.surefold.app.jobs

import io.github.matthewjones372.surefold.junit.Suite
import io.github.matthewjones372.surefold.junit.expect
import io.github.matthewjones372.surefold.junit.shouldBeEmpty
import java.io.File

/** What surefold-app-jobs may put on a service's classpath: surefold-app-liquibase's and surefold-jobs', no more. */
object NoOtherDependenciesTest : Suite() {

    private val allowed = listOf(
        "kotlin-stdlib",
        "annotations-",
        "surefold",
        // surefold-app-liquibase's: Liquibase and what it brings.
        "liquibase-core",
        "opencsv",
        "commons-lang3",
        "commons-text",
        "commons-collections4",
        "commons-io",
        "snakeyaml",
        "jaxb-api",
    )

    init {
        test("the main runtime classpath is surefold-app-liquibase and surefold-jobs, and nothing else") {
            val raw = System.getProperty("surefold.app.jobs.runtimeClasspath")
            expect(raw != null) { "the build must pass -Dsurefold.app.jobs.runtimeClasspath; see its build.gradle.kts" }
            raw!!.split(File.pathSeparator)
                .filter { it.isNotBlank() }
                .filterNot { entry -> allowed.any { entry.startsWith(it) } }
                .shouldBeEmpty()
        }
    }
}
