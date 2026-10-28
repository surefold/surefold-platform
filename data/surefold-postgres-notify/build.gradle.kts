// Postgres's LISTEN and NOTIFY as a surefold Stream (spec 0238): surefold-stream and the JDK's java.sql. No driver and
// no pool: the DataSource is the service's, and the driver's notifications are reached by reflection.
// NoOtherDependenciesTest asserts the runtime classpath.

dependencies {
    api(project(":surefold-stream"))

    // A run on surefold's own forks, against a real Postgres in a container.
    testImplementation(project(":surefold-stream-forks"))
    testImplementation(testFixtures(project(":surefold-actor-journal-jdbc")))
}

tasks.test {
    // GuideSuite holds docs/postgres-notify.md to this module's GuideExample.kt.
    inputs.file(rootProject.layout.projectDirectory.file("docs/postgres-notify.md")).withPropertyName("guide")
    systemProperty("surefold.postgres.notify.repoRoot", rootProject.projectDir.absolutePath)
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dsurefold.postgres.notify.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}
