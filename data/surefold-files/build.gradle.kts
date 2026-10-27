// Files on any S3-compatible store (spec 0224): the interface, an in-memory fake, and S3Files on the AWS SDK's S3
// client. The SDK runs on the JDK's own HTTP client, as surefold-cluster-aws does, so neither Netty nor Apache's
// client comes with it. NoOtherDependenciesTest asserts the runtime classpath.

val awsVersion = "2.55.6"

plugins {
    // The contract every Files must keep, for this module's fake and surefold-files-test's MinIO alike.
    `java-test-fixtures`
}

dependencies {
    api(project(":surefold"))
    api("software.amazon.awssdk:s3:$awsVersion") {
        exclude(group = "software.amazon.awssdk", module = "apache-client")
        exclude(group = "software.amazon.awssdk", module = "apache5-client")
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
    }
    implementation("software.amazon.awssdk:url-connection-client:$awsVersion")
    testFixturesApi(project(":surefold-junit"))
}

tasks.test {
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dsurefold.files.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}
