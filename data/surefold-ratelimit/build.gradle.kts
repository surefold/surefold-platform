// Rate limits shared by every instance (spec 0226): a token bucket per key, in memory or on Postgres through the
// JDK's java.sql. No driver and no pool: the DataSource is the service's. NoOtherDependenciesTest asserts the runtime
// classpath.

dependencies {
    api(project(":surefold"))

    testImplementation(testFixtures(project(":surefold-actor-journal-jdbc")))
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
