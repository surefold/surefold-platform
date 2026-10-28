// A cache whose every entry is one tenant's (spec 0246): surefold-cache and surefold-tenancy, and nothing else.
// NoOtherDependenciesTest asserts the runtime classpath.
dependencies {
    api(project(":surefold-cache"))
    api(project(":surefold-tenancy"))
}

tasks.test {
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dsurefold.cache.tenancy.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}
