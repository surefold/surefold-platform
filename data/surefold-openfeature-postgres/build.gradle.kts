// Flags per tenant from Postgres (spec 0240): an OpenFeature provider over surefold-openfeature, surefold-tenancy
// and surefold-cache.
// No driver and no pool: the DataSource is the service's. NoOtherDependenciesTest asserts the runtime classpath.
dependencies {
    api(project(":surefold-openfeature"))
    api(project(":surefold-tenancy"))
    api(project(":surefold-cache"))

    // A real Postgres, with roles that row-level security binds.
    testImplementation(testFixtures(project(":surefold-tenancy")))
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
