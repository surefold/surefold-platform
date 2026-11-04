// Rate limits shared by every instance (spec 0226): a token bucket per key, in memory or on Postgres through the
// JDK's java.sql. No driver and no pool: the DataSource is the service's. NoOtherDependenciesTest asserts the runtime
// classpath.
val surefoldCore: String by rootProject.extra

dependencies {
    api("dev.surefold:surefold:$surefoldCore")

    testImplementation(testFixtures("dev.surefold:surefold-actor-journal-jdbc:$surefoldCore"))
    testImplementation("org.liquibase:liquibase-core:4.32.0")
}

tasks.test {
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dsurefold.ratelimit.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}
