package dev.surefold.secrets.aws

import dev.surefold.junit.Suite
import dev.surefold.junit.expect
import dev.surefold.junit.shouldBeEmpty
import java.io.File

/** What surefold-secrets-aws may put on a service's classpath. */
object NoOtherDependenciesTest : Suite() {

    private val allowed = listOf(
        "kotlin-stdlib",
        "annotations-",
        "surefold",
        "secretsmanager-", "ssm-", "auth-", "aws-core-", "aws-json-protocol-", "checksums-", "endpoints-spi-",
        "eventstream-", "http-auth-", "http-client-spi-", "identity-spi-", "json-utils-", "metrics-spi-", "profiles-",
        "protocol-core-", "regions-", "retries-", "sdk-core-", "third-party-jackson-core-", "url-connection-client-",
        "utils-", "reactive-streams-", "slf4j-api-",
    )

    init {
        test("the main runtime classpath is surefold and the AWS Secrets Manager/SSM clients, and nothing else") {
            val raw = System.getProperty("surefold.secrets.aws.runtimeClasspath")
            expect(raw != null) {
                "the build must pass -Dsurefold.secrets.aws.runtimeClasspath; see its build file"
            }
            raw!!.split(File.pathSeparator)
                .filter { it.isNotBlank() }
                .filterNot { entry -> allowed.any { entry.startsWith(it) } }
                .shouldBeEmpty()
        }
    }
}
