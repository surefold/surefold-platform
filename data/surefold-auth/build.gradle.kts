// Sessions and credentials (specs 0227 and 0228): surefold, the JDK's java.sql and crypto, and Bouncy Castle for
// Argon2id, pure Java. No driver and no pool: the DataSource is the service's. NoOtherDependenciesTest asserts the
// runtime classpath.

dependencies {
    api(project(":surefold"))
    implementation("org.bouncycastle:bcprov-jdk18on:1.85")
}

tasks.test {
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
