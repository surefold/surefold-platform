// Secrets and parameters (spec 0046): SecretStore, ParameterStore, in-memory fakes, and RotatingDataSource.
// Depends only on surefold core and the JDK's sql module. NoOtherDependenciesTest asserts the runtime classpath.

val surefoldCore: String by rootProject.extra

dependencies {
    api("dev.surefold:surefold:$surefoldCore")
}

tasks.test {
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dsurefold.secrets.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}
