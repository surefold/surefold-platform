// MinIO in Testcontainers, for a service's own tests of what it stores (spec 0224), and where surefold-files' S3Files
// is held to the contract its fake keeps. NoOtherDependenciesTest asserts the runtime classpath.

dependencies {
    api(project(":surefold-files"))
    api("org.testcontainers:testcontainers:2.0.5")
    testImplementation(testFixtures(project(":surefold-files")))
}

tasks.test {
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dsurefold.files.test.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}
