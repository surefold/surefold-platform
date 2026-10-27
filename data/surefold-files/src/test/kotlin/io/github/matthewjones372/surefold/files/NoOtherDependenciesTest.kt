package io.github.matthewjones372.surefold.files

import io.github.matthewjones372.surefold.junit.Suite
import io.github.matthewjones372.surefold.junit.expect
import io.github.matthewjones372.surefold.junit.shouldBeEmpty
import java.io.File

/** What surefold-files may put on a service's classpath. */
object NoOtherDependenciesTest : Suite() {

    private val allowed = listOf("kotlin-stdlib", "annotations-", "surefold")

    init {
        test("the main runtime classpath is surefold, and nothing else") {
            val raw = System.getProperty("surefold.files.runtimeClasspath")
            expect(raw != null) { "the build must pass -Dsurefold.files.runtimeClasspath; see its build file" }
            raw!!.split(File.pathSeparator)
                .filter { it.isNotBlank() }
                .filterNot { entry -> allowed.any { entry.startsWith(it) } }
                .shouldBeEmpty()
        }
    }
}
