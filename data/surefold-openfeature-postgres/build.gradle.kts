// Flags per tenant from Postgres (spec 0240): an OpenFeature provider over surefold-openfeature, surefold-tenancy,
// surefold-cache, and surefold-postgres-notify to hear of a change.
// No driver and no pool: the DataSource is the service's. NoOtherDependenciesTest asserts the runtime classpath.
val surefoldCore: String by rootProject.extra
val surefoldIntegrations: String by rootProject.extra

dependencies {
    api("dev.surefold:surefold-openfeature:$surefoldIntegrations")
    api(project(":surefold-tenancy"))
    api("dev.surefold:surefold-cache:$surefoldCore")
    api(project(":surefold-postgres-notify"))

    // A real Postgres, with roles that row-level security binds.
    testImplementation(testFixtures(project(":surefold-tenancy")))
    // A listening run on surefold's own forks.
    testImplementation("dev.surefold:surefold-stream-forks:$surefoldCore")
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
