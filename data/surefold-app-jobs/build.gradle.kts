// surefold-app on surefold-jobs: a worker as a node, started after the migrations and closed on release.
// NoOtherDependenciesTest asserts the runtime classpath is surefold-app-liquibase and surefold-jobs, and nothing else.

dependencies {
    api(project(":surefold-app-liquibase"))
    api(project(":surefold-jobs"))

    // A real Postgres, in a container.
    testImplementation(testFixtures(project(":surefold-actor-journal-jdbc")))
}

tasks.test {
    // JobsGuideSuite holds docs/jobs.md to this module's GuideExample.kt.
    inputs.file(rootProject.layout.projectDirectory.file("docs/jobs.md")).withPropertyName("guide")
    systemProperty("surefold.app.jobs.repoRoot", rootProject.projectDir.absolutePath)
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dsurefold.app.jobs.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}
