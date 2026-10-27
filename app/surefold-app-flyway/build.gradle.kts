// surefold-app on Flyway Community (Apache-2.0): flyway-core, its Postgres support, and the JDK's javax.sql. No driver:
// the DataSource is the application's, and so is what it connects to.
val flywayVersion = "13.9.0"

dependencies {
    api(project(":surefold-app"))
    api("org.flywaydb:flyway-core:$flywayVersion")
    implementation("org.flywaydb:flyway-database-postgresql:$flywayVersion")

    // A real Postgres, in a container.
    testImplementation(testFixtures(project(":surefold-actor-journal-jdbc")))
    testImplementation(project(":surefold-assert"))
    // The data modules whose Flyway scripts SurefoldSchemaSuite holds equal to their Liquibase changelogs.
    testImplementation(project(":surefold-tenancy"))
    testImplementation(project(":surefold-jobs"))
    testImplementation(project(":surefold-idempotency"))
    testImplementation(project(":surefold-ratelimit"))
    testImplementation(project(":surefold-auth"))
}

tasks.test {
    // FlywayGuideSuite holds docs/flyway.md to this module's GuideExample.kt.
    inputs.file(rootProject.layout.projectDirectory.file("docs/flyway.md")).withPropertyName("guide")
    systemProperty("surefold.app.flyway.repoRoot", rootProject.projectDir.absolutePath)
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dsurefold.app.flyway.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}
