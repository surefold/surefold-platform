// Tenancy on Postgres row-level security (spec 0225): the JDK's java.sql and surefold. No driver and no pool: the
// DataSources are the service's. NoOtherDependenciesTest asserts the runtime classpath.

plugins {
    // A real Postgres with roles that row-level security binds and one it does not, for this module and the test kit.
    `java-test-fixtures`
}

val surefoldCore: String by rootProject.extra

dependencies {
    api("dev.surefold:surefold:$surefoldCore")

    testFixturesApi(testFixtures("dev.surefold:surefold-actor-journal-jdbc:$surefoldCore"))
    testFixturesImplementation("org.liquibase:liquibase-core:4.32.0")
}

tasks.test {
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dsurefold.tenancy.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}
