// A bank you can watch (spec 0094): accounts and transfers sharded across three nodes. An application, never
// published. NoOtherDependenciesTest asserts the runtime classpath is exactly what is declared here.

plugins {
    application
}

dependencies {
    implementation(project(":lark-cluster"))
    implementation(project(":lark-actor-journal-jdbc"))
    // The journal in memory by default, or on Postgres with `--jdbc`.
    implementation("com.h2database:h2:2.3.232")
    implementation("org.postgresql:postgresql:42.7.7")

    // A real Postgres for `--jdbc`'s test: a binary from Maven Central, run by the test JVM, no Docker.
    testImplementation("io.zonky.test:embedded-postgres:2.1.0")
    testImplementation(platform("io.zonky.test.postgres:embedded-postgres-binaries-bom:17.5.0"))
    // The pages in a real headless Chromium: the only proof they work. 1.56 drives Chromium 141, build 1194.
    testImplementation("com.microsoft.playwright:playwright:1.56.0")
}

application {
    mainClass.set("io.github.matthewjones372.lark.bank.MainKt")
}

// Playwright finds Chromium where the environment installed it, and never downloads one.
val browsers = providers.environmentVariable("PLAYWRIGHT_BROWSERS_PATH").orElse("/opt/pw-browsers")

tasks.test {
    environment("PLAYWRIGHT_BROWSERS_PATH", browsers.get())
    environment("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf("-Dlark.bank.runtimeClasspath=" + mainRuntime.get().joinToString(File.pathSeparator) { it.name })
        },
    )
}
