package dev.surefold.tenancy.crypto.gcp

import dev.surefold.junit.Suite
import dev.surefold.junit.expect
import dev.surefold.junit.shouldBeEmpty
import java.io.File

/** What surefold-tenancy-crypto-gcp may put on a service's classpath. */
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
            val raw = System.getProperty("surefold.tenancy.crypto.gcp.runtimeClasspath")
            expect(raw != null) {
                "the build must pass -Dsurefold.tenancy.crypto.gcp.runtimeClasspath; see its build file"
            }
            raw!!.split(File.pathSeparator)
                .filter { it.isNotBlank() }
                .filterNot { entry -> allowed.any { entry.startsWith(it) } }
                .shouldBeEmpty()
        }
    }
}
