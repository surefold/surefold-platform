// A span per run of a job (spec 0220): surefold-jobs and surefold-otel, so the OpenTelemetry API and nothing more.
// NoOtherDependenciesTest asserts the runtime classpath.

dependencies {
    api(project(":surefold-jobs"))
    api(project(":surefold-otel"))

    testImplementation(project(":surefold-otel-test"))
    testImplementation(testFixtures(project(":surefold-actor-journal-jdbc")))
    testImplementation("org.liquibase:liquibase-core:4.32.0")
}

tasks.test {
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dsurefold.jobs.otel.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}
