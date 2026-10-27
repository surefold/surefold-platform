// Idempotent requests on Postgres (spec 0226): the JDK's java.sql, surefold, and surefold-jobs for the prune of
// expired keys. No driver and no pool: the DataSource is the service's. NoOtherDependenciesTest asserts the runtime
// classpath.

dependencies {
    api(project(":surefold"))
    api(project(":surefold-jobs"))

    testImplementation(testFixtures(project(":surefold-actor-journal-jdbc")))
    testImplementation("org.liquibase:liquibase-core:4.32.0")
}

tasks.test {
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dsurefold.idempotency.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}
