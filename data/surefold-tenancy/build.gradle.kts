// Tenancy on Postgres row-level security (spec 0225): the JDK's java.sql and surefold. No driver and no pool: the
// DataSources are the service's. NoOtherDependenciesTest asserts the runtime classpath.

dependencies {
    api(project(":surefold"))
}

tasks.test {
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dsurefold.tenancy.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}
