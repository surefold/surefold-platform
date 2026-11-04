// Flags per tenant from Postgres (spec 0240): an OpenFeature provider over surefold-openfeature, surefold-tenancy,
// surefold-cache, and surefold-postgres-notify to hear of a change.
// No driver and no pool: the DataSource is the service's. NoOtherDependenciesTest asserts the runtime classpath.
dependencies {
    api(project(":surefold-openfeature"))
    api(project(":surefold-tenancy"))
    api(project(":surefold-cache"))
    api(project(":surefold-postgres-notify"))

    // A real Postgres, with roles that row-level security binds.
    testImplementation(testFixtures(project(":surefold-tenancy")))
    // A listening run on surefold's own forks.
    testImplementation(project(":surefold-stream-forks"))
}

tasks.test {
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dsurefold.openfeature.postgres.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}
