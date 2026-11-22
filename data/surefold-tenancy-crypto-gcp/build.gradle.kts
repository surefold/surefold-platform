// surefold-tenancy-crypto on GCP Cloud KMS over the JDK's HttpClient (spec 0046): zero gRPC or Google SDK
// dependencies. NoOtherDependenciesTest asserts the runtime classpath.

dependencies {
    api(project(":surefold-tenancy-crypto"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
}

tasks.test {
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dsurefold.tenancy.crypto.gcp.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}
