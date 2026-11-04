// surefold-cache's shared layer on Redis, and invalidation over Redis pub/sub (spec 0246): surefold-cache and the
// Lettuce client, with the Netty and Reactor it runs on. NoOtherDependenciesTest asserts the runtime classpath.
dependencies {
    api(project(":surefold-cache"))
    api("io.lettuce:lettuce-core:6.5.0.RELEASE")

    // A real Redis, in a container.
    testImplementation("org.testcontainers:testcontainers:2.0.5")
}

tasks.test {
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dsurefold.cache.redis.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}
