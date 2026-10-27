// Files on any S3-compatible store (spec 0224): the interface, its failures and an in-memory fake on surefold alone.
// NoOtherDependenciesTest asserts the runtime classpath.

plugins {
    // The contract every Files must keep, for this module's fake and surefold-files-test's MinIO alike.
    `java-test-fixtures`
}

dependencies {
    api(project(":surefold"))
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
