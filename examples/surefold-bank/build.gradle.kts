// A bank you can watch (spec 0094): keyed accounts and transfer sagas sharded across three nodes (specs 0299,
// 0300). An application, never published. NoOtherDependenciesTest asserts the runtime classpath is exactly what is declared here.

plugins {
    application
    kotlin("plugin.serialization")
}

val surefoldCore: String by rootProject.extra
val surefoldIntegrations: String by rootProject.extra

dependencies {
    implementation("dev.surefold:surefold-cluster:$surefoldCore")
    // The transfer is a durable saga (spec 0299), each step an entityStep over a keyed account.
    implementation("dev.surefold:surefold-cluster-saga:$surefoldCore")
    implementation("dev.surefold:surefold-actor-journal-jdbc:$surefoldCore")
    implementation("dev.surefold:surefold-app-liquibase:$surefoldIntegrations")
    // The journal in memory by default, or on Postgres with `--jdbc`.
    implementation("org.postgresql:postgresql:42.7.7")
    implementation("com.zaxxer:HikariCP:7.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    // A real Postgres, in a container.
    testImplementation(testFixtures("dev.surefold:surefold-actor-journal-jdbc:$surefoldCore"))
    // The pages in a real headless Chromium: the only proof they work. 1.56 drives Chromium 141, build 1194.
    testImplementation("com.microsoft.playwright:playwright:1.56.0")
    testImplementation("dev.surefold:surefold-assert:$surefoldCore")
    // freePort(): a port below the ephemeral range, never handed out twice in a JVM (spec 0197).
    testImplementation(testFixtures("dev.surefold:surefold-actor-remote:$surefoldCore"))
}

application {
    mainClass.set("dev.surefold.bank.MainKt")
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
            listOf("-Dsurefold.bank.runtimeClasspath=" + mainRuntime.get().joinToString(File.pathSeparator) { it.name })
        },
    )
}

// An AOT cache (spec 0278) is made from the installed jars and used with the same ones, in the same order. The JVM
// refuses one for a classpath with directories on it, and for one that does not start with the classpath it was
// made from.
val installedLib = tasks.named<Sync>("installDist").map { it.destinationDir.resolve("lib") }
val bankJar = tasks.named<Jar>("jar").flatMap { it.archiveFileName }
val aotClasspath = installedLib.zip(bankJar) { lib, jar ->
    listOf(lib.resolve(jar)) + configurations.runtimeClasspath.get().map { lib.resolve(it.name) }
}
val aotCacheFile = layout.buildDirectory.file("aot/bank.aot")

/** One `--train` run on the installed jars, which writes the cache as the JVM leaves. */
val aotCache = tasks.register<JavaExec>("aotCache") {
    group = "application"
    description = "Makes the bank's AOT cache from a training run (spec 0278)"
    dependsOn("installDist")
    mainClass.set(application.mainClass)
    classpath(aotClasspath)
    inputs.files(aotClasspath).withPropertyName("aotClasspath")
    outputs.file(aotCacheFile)
    args("--train")
    jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-XX:AOTCacheOutput=${aotCacheFile.get().asFile}") })
    doFirst { aotCacheFile.get().asFile.parentFile.mkdirs() }
}

/** The bank on the installed jars and its cache, which it will not start without. `-PbankArgs="--train"` times it. */
tasks.register<JavaExec>("runCached") {
    group = "application"
    description = "Runs the bank with its AOT cache, failing if the JVM cannot use it (spec 0278)"
    dependsOn(aotCache)
    mainClass.set(application.mainClass)
    classpath(aotClasspath)
    args(providers.gradleProperty("bankArgs").getOrElse("").split(" ").filter { it.isNotBlank() })
    jvmArgumentProviders.add(
        CommandLineArgumentProvider { listOf("-XX:AOTCache=${aotCacheFile.get().asFile}", "-XX:AOTMode=on") },
    )
}

// AotCacheSuite trains a JVM of its own and starts another on its cache, both on the installed jars.
tasks.test {
    dependsOn("installDist")
    inputs.files(aotClasspath).withPropertyName("aotClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf("-Dsurefold.bank.aotClasspath=" + aotClasspath.get().joinToString(File.pathSeparator))
        },
    )
}
