package io.github.matthewjones372.surefold.jobs.tenancy

import io.github.matthewjones372.surefold.junit.Suite
import io.github.matthewjones372.surefold.junit.expect
import io.github.matthewjones372.surefold.junit.shouldBeEmpty
import java.io.File

/** What surefold-jobs-tenancy may put on a service's classpath: surefold-jobs' and surefold-tenancy's, no more. */
object NoOtherDependenciesTest : Suite() {

    private val allowed = listOf("kotlin-stdlib", "annotations-", "surefold")

    init {
        test("the main runtime classpath is surefold-jobs and surefold-tenancy, and nothing else") {
            val raw = System.getProperty("surefold.jobs.tenancy.runtimeClasspath")
            expect(raw != null) { "the build must pass -Dsurefold.jobs.tenancy.runtimeClasspath; see its build file" }
            raw!!.split(File.pathSeparator)
                .filter { it.isNotBlank() }
                .filterNot { entry -> allowed.any { entry.startsWith(it) } }
                .shouldBeEmpty()
        }
    }
}
