// A key per tenant, rooted in a KMS key that no backup holds (spec 0235): surefold-tenancy and the JDK's javax.crypto.
// The KMS is a port; surefold-tenancy-crypto-aws is AWS's. NoOtherDependenciesTest asserts the runtime classpath.

dependencies {
    api(project(":surefold-tenancy"))

    // A real Postgres with roles row-level security binds, one it does not, and one that bypasses it.
    testImplementation(testFixtures(project(":surefold-tenancy")))
}

tasks.test {
    val guide = rootProject.layout.projectDirectory.file("docs/tenancy-crypto.md")
    val example = layout.projectDirectory.file(
        "src/test/kotlin/dev/surefold/tenancy/crypto/KeyCacheCompatibilitySuite.kt",
    )
    inputs.file(guide).withPropertyName("retainedKeyViewsGuide")
    inputs.file(example).withPropertyName("retainedKeyViewsExample")
    systemProperty("surefold.tenancy.crypto.keyViewGuide", guide.asFile.absolutePath)
    systemProperty("surefold.tenancy.crypto.keyViewExample", example.asFile.absolutePath)

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
