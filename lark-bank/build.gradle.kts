// A bank you can watch (spec 0094): accounts and transfers sharded across three nodes. An application, never
// published. NoOtherDependenciesTest asserts the runtime classpath is exactly what is declared here.

plugins {
    application
}

dependencies {
    implementation(project(":lark-cluster"))
    implementation(project(":lark-actor-journal-jdbc"))
    implementation(project(":lark-app-liquibase"))
    // The journal in memory by default, or on Postgres with `--jdbc`.
    implementation("org.postgresql:postgresql:42.7.7")
    implementation("com.zaxxer:HikariCP:7.1.0")

    // A real Postgres, in a container.
    testImplementation(testFixtures(project(":lark-actor-journal-jdbc")))
    // The pages in a real headless Chromium: the only proof they work. 1.56 drives Chromium 141, build 1194.
    testImplementation("com.microsoft.playwright:playwright:1.56.0")
}

application {
    mainClass.set("io.github.matthewjones372.lark.bank.MainKt")
}

// Where the environment names its installed browsers, Playwright uses that Chromium and downloads none. Where it
// names none, as on CI, Playwright downloads its own.
val browsers = providers.environmentVariable("PLAYWRIGHT_BROWSERS_PATH")

tasks.test {
    if (browsers.isPresent) {
        environment("PLAYWRIGHT_BROWSERS_PATH", browsers.get())
        environment("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")
    }
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf("-Dlark.bank.runtimeClasspath=" + mainRuntime.get().joinToString(File.pathSeparator) { it.name })
        },
    )
}
