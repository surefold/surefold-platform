package dev.surefold.secrets.gcp

import dev.surefold.junit.Suite
import dev.surefold.junit.expect
import dev.surefold.junit.shouldBeEmpty
import java.io.File

/** What surefold-secrets-gcp may put on a service's classpath. */
object NoOtherDependenciesTest : Suite() {

    private val allowed = listOf(
        "kotlin-stdlib",
        "annotations-",
        "surefold",
        "kotlinx-serialization-core-jvm-",
        "kotlinx-serialization-json-jvm-",
    )

    init {
        test("the main runtime classpath is surefold and kotlinx-serialization-json, with no gRPC or Google SDK") {
            val raw = System.getProperty("surefold.secrets.gcp.runtimeClasspath")
            expect(raw != null) {
                "the build must pass -Dsurefold.secrets.gcp.runtimeClasspath; see its build file"
            }
            raw!!.split(File.pathSeparator)
                .filter { it.isNotBlank() }
                .filterNot { entry -> allowed.any { entry.startsWith(it) } }
                .shouldBeEmpty()
        }
    }
}
