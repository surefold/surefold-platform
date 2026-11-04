// surefold-jobs on surefold-tenancy (spec 0225): each job's handler runs as the tenant its row names.
// NoOtherDependenciesTest asserts the runtime classpath.
val surefoldCore: String by rootProject.extra

dependencies {
    api(project(":surefold-jobs"))
    api(project(":surefold-tenancy"))

    testImplementation(testFixtures("dev.surefold:surefold-actor-journal-jdbc:$surefoldCore"))
    testImplementation("org.liquibase:liquibase-core:4.32.0")
}

tasks.test {
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dsurefold.jobs.tenancy.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}
