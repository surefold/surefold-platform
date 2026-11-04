plugins {
    kotlin("jvm") version "2.4.10" apply false
    // @Serializable classes: surefold-actor-remote-kotlinx's tests, and the services that use it (spec 0093).
    kotlin("plugin.serialization") version "2.4.10" apply false
    // A failed `expect` in surefold-junit's suites draws its values (spec 0175).
    kotlin("plugin.power-assert") version "2.4.10" apply false
    id("com.diffplug.spotless") version "8.10.0"
    id("dev.detekt") version "2.0.0-alpha.6" apply false
    id("org.jetbrains.kotlinx.kover") version "0.9.9"
    // The version comes from the nearest `v` tag rather than a property, so
    // cutting a release is `git tag v0.1.0 && git push --tags` and nothing
    // else. An untagged commit is a -SNAPSHOT of the next one.
    id("pl.allegro.tech.build.axion-release") version "1.21.3"
    // So the root project has `check`/`build`, and the scripts formatted here
    // are covered by a plain `./gradlew build` like everything else.
    base
    // Publishing to the Central Portal, which is the only way in since OSSRH
    // closed. It wraps `maven-publish` and `signing`, so neither is applied
    // here directly.
    id("com.vanniktech.maven.publish") version "0.37.0" apply false
    // Renders the KDoc into the javadoc jar the published module ships.
    id("org.jetbrains.dokka") version "2.1.0" apply false
}

scmVersion {
    tag {
        prefix.set("v")
        // Before this repository's first tag: these modules were released from surefold-core up to 0.18.0 under the
        // same coordinates, so a snapshot here has to sort above that rather than start again at 0.1.0.
        initialVersion { _, _ -> "0.18.1" }
    }
    // Plain `0.1.0-SNAPSHOT` off a tag rather than axion's default, which
    // decorates it with the branch name. The README tells a contributor to
    // install locally and depend on the version; that version should not
    // change with the branch they happen to be on.
    versionCreator("simple")
}

// Read once, at configuration time: `scmVersion.version` shells out to git.
val scmVer: String = scmVersion.version

/**
 * ktlint rather than ktfmt: ktfmt reflows, and this codebase is hand-laid-out
 * on purpose — banner comments, aligned trailing comments, KDoc written as
 * prose. The rules that make ktlint disagree with any of that are turned off
 * in `.editorconfig`, which is also where the line length lives.
 */
val ktlintVersion = "1.8.0"

/**
 * The Kotlin this build compiles with, for the two modules that compile against the compiler itself.
 *
 * Written once: a plugin built against one compiler and loaded into another fails at a consumer's
 * compile, or quietly in their editor, and a version number repeated in three build scripts is three
 * chances for that to happen on a bump.
 */
val kotlinVersion: String = "2.4.10"

// Spotless does not pick these up from .editorconfig for every source set, so
// they are handed to the ktlint step directly. The reasoning for each lives in
// .editorconfig beside the rest, which is also what the IDE reads.
val ktlintOverrides = mapOf("ktlint_standard_kdoc" to "disabled")

// The root project builds nothing, but Spotless resolves ktlint here.
repositories { mavenCentral() }

spotless {
    kotlinGradle {
        // settings.gradle.kts and this file. The module formats its own build
        // script in the `subprojects` block below.
        target("*.gradle.kts")
        ktlint(ktlintVersion).editorConfigOverride(ktlintOverrides)
    }
}

/** One line per module, so a Maven search result says what the artifact is. */
val moduleDescriptions = mapOf(
    "surefold-app-flyway" to
        "surefold-app on Flyway: SQL migrations as a node, with the settings a production database wants pinned.",
    "surefold-jobs" to
        "Durable jobs on Postgres, enqueued in the caller's own transaction so they commit with its change.",
    "surefold-app-jobs" to
        "surefold-app on surefold-jobs: a worker is a node, started after the migrations and drained on release.",
    "surefold-jobs-otel" to
        "surefold-jobs on OpenTelemetry: a span per run of a job, linked to the span that enqueued it.",
    "surefold-files" to
        "Files on any S3-compatible store: put, get, list and presigned URLs, typed failures, and a fake for tests.",
    "surefold-files-test" to
        "MinIO in Testcontainers for a service's own tests of surefold-files: a fresh bucket a call.",
    "surefold-tenancy" to
        "Tenancy Postgres enforces: a tenant forks inherit, row-level security per transaction, and an audited bypass.",
    "surefold-tenancy-test" to "A check that a service's tables keep two tenants apart under row-level security.",
    "surefold-jobs-tenancy" to
        "surefold-jobs on surefold-tenancy: each job's handler runs as the tenant its row names.",
    "surefold-idempotency" to
        "Idempotent requests on Postgres: keys reserved atomically across instances, and answers replayed.",
    "surefold-ratelimit" to "Rate limits as token buckets, in memory or on Postgres, shared by every instance.",
    "surefold-tenancy-crypto" to
        "A key per tenant, rooted in a KMS key no backup holds: delete the root and every copy is unreadable.",
    "surefold-tenancy-crypto-aws" to
        "surefold-tenancy-crypto on AWS KMS: one customer managed key per tenant, found by alias.",
    "surefold-openfeature-postgres" to
        "Feature flags per tenant from a Postgres table, read under its row-level security policy, for OpenFeature.",
    "surefold-cache-redis" to
        "surefold-cache on Redis: a layer every instance shares, and invalidation over Redis pub/sub.",
    "surefold-cache-tenancy" to
        "A surefold-cache whose every entry is one tenant's, looked up as that tenant, and refused with no tenant.",
    "surefold-cache-postgres" to
        "A surefold-cache told by Postgres NOTIFY that a row changed: a payload drops its key, a gap every key.",
    "surefold-postgres-notify" to
        "Postgres's LISTEN and NOTIFY as a surefold Stream: one connection, notifications in commit order.",
    "surefold-auth" to
        "Sessions and credentials on Postgres: Argon2id passwords, rotating sessions, magic links, TOTP, roles.",
    "surefold-auth-oidc" to
        "OpenID Connect sign-in on Nimbus: the code flow with PKCE, and each ID token check its own error.",
)

// Measured, not tested: a benchmark has no assertions for a coverage floor to count.
val benchmarkModules = setOf("surefold-jobs-load")

// Built and tested, never published: surefold-bank is an application to run, not a library to depend on (surefold-core's
// spec 0094).
val unpublished = setOf("surefold-bank") + benchmarkModules

// The modules a snapshot or a release publishes, one per line: snapshot.yml checks each is on GitHub Packages after.
tasks.register("publishedModules") {
    val names = subprojects.map { it.name }.filterNot { it in unpublished }.sorted()
    doLast { names.forEach(::println) }
}

/** The upstream snapshots this build is pinned to (gradle.properties), or their source beside this checkout. */
val surefoldCore: String = providers.gradleProperty("surefoldCore").get()
val surefoldIntegrations: String = providers.gradleProperty("surefoldIntegrations").get()

/** The upstream repositories settings.gradle.kts included from beside this checkout, whose projects replace the pins. */
val fromSource: Set<String> = gradle.includedBuilds.map { it.projectDir.name }.toSet()

// The floor is a ratchet against regression, not a target to code towards — a
// test written to move a percentage is worth less than no test at all.
kover {
    reports {
        total {
            verify {
                rule {
                    minBound(90)
                }
            }
        }
    }
}

dependencies {
    subprojects.filterNot { it.name in benchmarkModules }.forEach { kover(project(it.path)) }
}

// A floor nobody runs is not a floor: `./gradlew build` checks it.
tasks.named("check") {
    dependsOn("koverVerify")
}

extra["surefoldKotlinVersion"] = kotlinVersion
extra["surefoldCore"] = surefoldCore
extra["surefoldIntegrations"] = surefoldIntegrations

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    repositories {
        mavenCentral()
        // The upstream snapshots (surefold-core's spec 0281). GitHub Packages asks for a token even to read a public
        // package: a classic personal access token with read:packages, as `githubPackagesUsername` and
        // `githubPackagesPassword`. Not for an upstream checked out beside this one, whose source stands in for it.
        // Integrations first: core published those modules too until surefold-core's spec 0302 removed them, and its
        // copies of them must never stand in for the integrations' own.
        listOf("surefold-integrations", "surefold-core").filterNot { it in fromSource }.forEach { upstream ->
            maven {
                name = upstream
                url = uri("https://maven.pkg.github.com/surefold/$upstream")
                credentials {
                    username = providers.gradleProperty("githubPackagesUsername").orNull
                    password = providers.gradleProperty("githubPackagesPassword").orNull
                }
                content { includeGroup("dev.surefold") }
            }
        }
    }

    // The namespace verified on the Central Portal, against the GitHub
    // account it names.
    group = "dev.surefold"
    version = scmVer

    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        // 25: the first LTS where a virtual thread blocked in `synchronized` no longer pins its carrier, which
        // every library here runs on.
        jvmToolchain(25)

        compilerOptions {
            // A warning nobody reads is a defect nobody fixed; the build says so.
            allWarningsAsErrors.set(true)
            // Without this, every interface with a method body also gets a
            // `DefaultImpls` class holding a copy of it, and both are published
            // surface the BCV gate then has to keep. The interfaces here
            // already emit real JVM default methods, so the copies are the ABI
            // of a compatibility mode with nothing behind it: Kotlin callers
            // never reach them, and the Java caller they exist for would have
            // had to write `JsonValue.DefaultImpls.x(this)` by hand.
            jvmDefault.set(org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.NO_COMPATIBILITY)
        }
    }

    dependencies {
        "testImplementation"(kotlin("test"))
        "testImplementation"("org.junit.jupiter:junit-jupiter:6.1.3")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    // A module's tests are surefold-junit suites (surefold-core's spec 0197), and a failed `expect` draws its values.
    if (name !in benchmarkModules) {
        apply(plugin = "org.jetbrains.kotlin.plugin.power-assert")
        dependencies { "testImplementation"("dev.surefold:surefold-junit:$surefoldCore") }
        @OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
        extensions.configure<org.jetbrains.kotlin.powerassert.gradle.PowerAssertGradleExtension> {
            functions.add("dev.surefold.junit.expect")
            includedSourceSets.add("test")
        }
        // The checker reports the standard library's dropped must-use values, and only under it does `val _ =` compile.
        tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileTestKotlin") {
            compilerOptions.freeCompilerArgs.add("-Xreturn-value-checker=check")
        }
    }

    tasks.withType<Test>().configureEach {
        // `-PincludeTags=io,db` runs only the tests tagged so: Jupiter's @Tag and surefold-junit's tag() alike.
        val included = providers.gradleProperty("includeTags").orNull?.split(',')?.filter { it.isNotBlank() }
        useJUnitPlatform { included?.let { includeTags(*it.toTypedArray()) } }

        systemProperty("junit.jupiter.execution.timeout.default", "60s")
        // The same default for surefold-junit suites, which Jupiter's property does not reach (spec 0193).
        systemProperty("surefold.junit.timeout.default", "60s")
    }

    if (name !in benchmarkModules) {
        apply(plugin = "org.jetbrains.kotlinx.kover")
    }

    apply(plugin = "dev.detekt")
    extensions.configure<dev.detekt.gradle.extensions.DetektExtension> {
        // The deviations live in one file for the whole build; see its header.
        config.setFrom(rootProject.file("config/detekt/detekt.yml"))
        buildUponDefaultConfig = true
    }
    // The plain `detekt` task cannot see types, so the rules that need them —
    // ForbiddenMethodCall, for one — are silently skipped there. `check`
    // depends on the type-resolving pair instead.
    tasks.named("check") { dependsOn("detektMain", "detektTest") }
    // A source detekt cannot compile is analysed without its types, and detekt
    // only prints a count of the errors and passes: it has no setting to fail
    // instead, so each task watches its own output. Detekt runs on the task's
    // thread, and other tasks print to the same listeners in parallel, so only
    // that thread's lines count. The usual cause is a call to code a compiler
    // plugin generates, such as `Paid.serializer()`: detekt runs no compiler
    // plugins, and `serializer<Paid>()` resolves without one.
    tasks.withType<dev.detekt.gradle.Detekt>().configureEach {
        val running = java.util.concurrent.atomic.AtomicReference<Thread>()
        val compilerErrors = java.util.concurrent.ConcurrentLinkedQueue<String>()
        doFirst { running.set(Thread.currentThread()) }
        logging.addStandardOutputListener {
            if (Thread.currentThread() == running.get() && "compiler errors found during analysis" in it) {
                compilerErrors += it.toString().trim()
            }
        }
        doLast {
            if (compilerErrors.isNotEmpty()) {
                throw GradleException(
                    "$path: ${compilerErrors.joinToString()} Set `debug = true` on the task to see them.",
                )
            }
        }
    }

    apply(plugin = "com.diffplug.spotless")
    extensions.configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        kotlin {
            target("src/**/*.kt")
            ktlint(ktlintVersion).editorConfigOverride(ktlintOverrides)
        }
        kotlinGradle {
            target("*.gradle.kts")
            ktlint(ktlintVersion).editorConfigOverride(ktlintOverrides)
        }
    }

    if (name in unpublished) return@subprojects

    apply(plugin = "com.vanniktech.maven.publish")
    apply(plugin = "org.jetbrains.dokka")

    extensions.configure<com.vanniktech.maven.publish.MavenPublishBaseExtension> {
        // Sources are not an optional extra for a library someone else has to
        // debug, and Maven Central will not accept a release without a javadoc
        // jar. An empty one: Dokka's were 16 of surefold-core 0.18.0's 19 MB on
        // Central, nearly all of it the same scripts and fonts in every module,
        // and Central now caps a publisher's monthly release size. javadoc.io is
        // blank as a result; `dokkaGeneratePublicationHtml` still renders the
        // KDoc locally, and the sources jar carries it to an IDE.
        run {
            configure(
                com.vanniktech.maven.publish.KotlinJvm(
                    javadocJar = com.vanniktech.maven.publish.JavadocJar.Empty(),
                    sourcesJar = true,
                ),
            )
        }

        pom {
            name.set(this@subprojects.name)
            description.set(moduleDescriptions[this@subprojects.name] ?: "Part of Surefold.")
            url.set("https://github.com/surefold/surefold-platform")
            licenses {
                license {
                    name.set("The Apache License, Version 2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                }
            }
            developers {
                developer {
                    id.set("matthewjones372")
                    name.set("Matt Jones")
                }
            }
            scm {
                url.set("https://github.com/surefold/surefold-platform")
                connection.set("scm:git:https://github.com/surefold/surefold-platform.git")
                developerConnection.set("scm:git:ssh://git@github.com/surefold/surefold-platform.git")
            }
        }
    }

    // A tag gives axion a version with no `-SNAPSHOT`, which makes Gradle's signing
    // required — and `build` signs the publications, so the gate starts depending on
    // a key. The release job has one; the gate build and every pull request do not,
    // and handing them the signing key to run a task nobody publishes from would be
    // the wrong way round. Signed where there is something to sign with, skipped
    // where there is not, and `publishToMavenCentral` still fails loudly on an
    // unsigned artifact.
    tasks.withType<Sign>().configureEach {
        onlyIf { !(project.findProperty("signingInMemoryKey") as? String).isNullOrBlank() }
    }

    extensions.configure<PublishingExtension> {
        repositories {
            // `./gradlew publishToMavenLocal` for a local try-out, and
            // `publishAllPublicationsToLocalRepository` for something to
            // inspect without installing it.
            maven {
                name = "local"
                url = rootProject.layout.buildDirectory.dir("repo").get().asFile.toURI()
            }
            // Snapshots (spec 0281): snapshot.yml runs `publishAllPublicationsToGithubPackagesRepository` with the
            // workflow's token as `githubPackagesUsername`/`githubPackagesPassword`. Releases stay on Central.
            maven {
                name = "githubPackages"
                url = uri("https://maven.pkg.github.com/surefold/surefold-platform")
                credentials(PasswordCredentials::class)
            }
        }
        // No relocation POMs at `io.github.matthewjones372` any more (surefold-core's spec 0277 published them at
        // 0.18.0): 0.18.0's send a build on the old group here, and every further version of them would be a
        // second Central deployment per release, which Central now counts against a monthly limit.
    }
}
