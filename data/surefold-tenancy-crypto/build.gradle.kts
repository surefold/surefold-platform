// A key per tenant, rooted in a KMS key that no backup holds (spec 0235): surefold-tenancy and the JDK's javax.crypto.
// The KMS is a port; surefold-tenancy-crypto-aws is AWS's. NoOtherDependenciesTest asserts the runtime classpath.

dependencies {
    api(project(":surefold-tenancy"))
}

tasks.test {
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dsurefold.tenancy.crypto.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}
