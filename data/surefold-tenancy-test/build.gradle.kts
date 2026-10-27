// Checks that a service's tables keep its tenants apart (spec 0225): surefold-tenancy and nothing else.
// NoOtherDependenciesTest asserts the runtime classpath.

dependencies {
    api(project(":surefold-tenancy"))
    testImplementation(testFixtures(project(":surefold-tenancy")))
    testImplementation("org.liquibase:liquibase-core:4.32.0")
}

tasks.test {
    // GuideSuite holds docs/tenancy.md's changelog to this module's test resource, which the suite applies.
    inputs.file(rootProject.layout.projectDirectory.file("docs/tenancy.md")).withPropertyName("guide")
    systemProperty("surefold.tenancy.test.repoRoot", rootProject.projectDir.absolutePath)
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dsurefold.tenancy.test.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}
