package dev.surefold.secrets

import dev.surefold.junit.Suite
import dev.surefold.junit.expect
import dev.surefold.junit.shouldBeEmpty
import java.io.File

/** What surefold-secrets may put on a service's classpath. */
object NoOtherDependenciesTest : Suite() {

    private val allowed = listOf(
        "kotlin-stdlib",
        "annotations-",
        "surefold",
    )

    init {
        test("the main runtime classpath is surefold and nothing else") {
            val raw = System.getProperty("surefold.secrets.runtimeClasspath")
            expect(raw != null) { "the build must pass -Dsurefold.secrets.runtimeClasspath; see its build file" }
            raw!!.split(File.pathSeparator)
                .filter { it.isNotBlank() }
                .filterNot { entry -> allowed.any { entry.startsWith(it) } }
                .shouldBeEmpty()
        }
    }
}
