// surefold-secrets on GCP Secret Manager over the JDK's HttpClient (spec 0046): zero gRPC or Google SDK dependencies.
// NoOtherDependenciesTest asserts the runtime classpath.

dependencies {
    api(project(":surefold-secrets"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
}

tasks.test {
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dsurefold.secrets.gcp.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}
