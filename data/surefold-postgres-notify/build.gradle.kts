// Postgres's LISTEN and NOTIFY as a surefold Stream (spec 0238): surefold-stream and the JDK's java.sql. No driver and
// no pool: the DataSource is the service's, and the driver's notifications are reached by reflection.
// NoOtherDependenciesTest asserts the runtime classpath.
val surefoldCore: String by rootProject.extra

dependencies {
    api("dev.surefold:surefold-stream:$surefoldCore")

    // A run on surefold's own forks, against a real Postgres in a container.
    testImplementation("dev.surefold:surefold-stream-forks:$surefoldCore")
    testImplementation(testFixtures("dev.surefold:surefold-actor-journal-jdbc:$surefoldCore"))
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
