// Sessions and credentials (specs 0227 and 0228): surefold, the JDK's java.sql and crypto, and Bouncy Castle for
// Argon2id, pure Java. No driver and no pool: the DataSource is the service's. NoOtherDependenciesTest asserts the
// runtime classpath.

dependencies {
    api(project(":surefold"))
    implementation("org.bouncycastle:bcprov-jdk18on:1.85")

    testImplementation(testFixtures(project(":surefold-actor-journal-jdbc")))
    testImplementation("org.liquibase:liquibase-core:4.32.0")
}

tasks.test {
    // AuthGuideSuite holds docs/auth.md to this module's GuideExample.kt.
    inputs.file(rootProject.layout.projectDirectory.file("docs/auth.md")).withPropertyName("guide")
    systemProperty("surefold.auth.repoRoot", rootProject.projectDir.absolutePath)
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dsurefold.auth.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}
