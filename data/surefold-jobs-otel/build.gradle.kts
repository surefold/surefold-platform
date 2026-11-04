// A span per run of a job (spec 0220): surefold-jobs and surefold-otel, so the OpenTelemetry API and nothing more.
// NoOtherDependenciesTest asserts the runtime classpath.
val surefoldCore: String by rootProject.extra
val surefoldIntegrations: String by rootProject.extra

dependencies {
    api(project(":surefold-jobs"))
    api("dev.surefold:surefold-otel:$surefoldIntegrations")

    testImplementation("dev.surefold:surefold-otel-test:$surefoldIntegrations")
    testImplementation(testFixtures("dev.surefold:surefold-actor-journal-jdbc:$surefoldCore"))
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
