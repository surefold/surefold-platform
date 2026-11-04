// A cache told by Postgres when a value changed (spec 0246): surefold-cache and surefold-postgres-notify, and nothing
// else. NoOtherDependenciesTest asserts the runtime classpath.
val surefoldCore: String by rootProject.extra

dependencies {
    api("dev.surefold:surefold-cache:$surefoldCore")
    api(project(":surefold-postgres-notify"))

    // A run on surefold's own forks, against a real Postgres in a container.
    testImplementation("dev.surefold:surefold-stream-forks:$surefoldCore")
    testImplementation(testFixtures("dev.surefold:surefold-actor-journal-jdbc:$surefoldCore"))
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
