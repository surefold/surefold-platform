// Durable jobs on Postgres (spec 0216): the JDK's java.sql and nothing else. No driver, no pool and no JSON library:
// the DataSource is the service's, and a payload is its codec's text. A worker listens through
// surefold-postgres-notify, run on surefold's own forks (spec 0238). NoOtherDependenciesTest asserts the runtime
// classpath.

dependencies {
    api(project(":surefold"))
    implementation(project(":surefold-postgres-notify"))
    implementation(project(":surefold-stream-forks"))

    // A real Postgres, in a container, with the changelog applied as a service would.
    testImplementation(testFixtures(project(":surefold-actor-journal-jdbc")))
    testImplementation("org.liquibase:liquibase-core:4.32.0")
}

tasks.test {
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dsurefold.jobs.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}
