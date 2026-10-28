// A cache told by Postgres when a value changed (spec 0246): surefold-cache and surefold-postgres-notify, and nothing
// else. NoOtherDependenciesTest asserts the runtime classpath.
dependencies {
    api(project(":surefold-cache"))
    api(project(":surefold-postgres-notify"))

    // A run on surefold's own forks, against a real Postgres in a container.
    testImplementation(project(":surefold-stream-forks"))
    testImplementation(testFixtures(project(":surefold-actor-journal-jdbc")))
}

tasks.test {
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dsurefold.cache.postgres.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}
