// Where a Java toolchain comes from when the machine does not already have it.
// Gradle deprecated auto-provisioning without a declared resolver, so the build
// says which one it uses rather than relying on a default that is going away.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "surefold-platform"

// Each module lives in its family's folder but keeps a flat project path, as in surefold-core (its spec 0139).
val modules = mutableSetOf<String>()

fun module(group: String, name: String) {
    include(name)
    project(":$name").projectDir = file("$group/$name")
    modules += name
}

module("app", "surefold-app-flyway")

module("data", "surefold-jobs")
module("data", "surefold-app-jobs")
module("data", "surefold-jobs-otel")
module("data", "surefold-files")
module("data", "surefold-files-test")
module("data", "surefold-tenancy")
module("data", "surefold-tenancy-test")
module("data", "surefold-jobs-tenancy")
module("data", "surefold-idempotency")
module("data", "surefold-ratelimit")
module("data", "surefold-postgres-notify")
module("data", "surefold-cache-postgres")
module("data", "surefold-cache-tenancy")
module("data", "surefold-cache-redis")
module("data", "surefold-openfeature-postgres")
module("data", "surefold-tenancy-crypto")
module("data", "surefold-tenancy-crypto-aws")
module("data", "surefold-tenancy-crypto-gcp")
module("data", "surefold-secrets")
module("data", "surefold-secrets-aws")
module("data", "surefold-secrets-gcp")
module("data", "surefold-auth")
module("data", "surefold-auth-oidc")

module("bench", "surefold-jobs-load")

module("examples", "surefold-bank")

// Upstream source beside this checkout, when it is there (surefold-core's specs 0301 and 0302): Gradle substitutes
// their projects for the `dev.surefold` artifacts pinned in gradle.properties, so a change to core or the
// integrations reaches the tests here without publishing. CI never checks them out, so it always builds against the
// pins; `-Psurefold.upstreamSource=false` does the same with a checkout beside this one.
//
// Each upstream stands in only for the modules it is the home of. Until surefold-core's spec 0302 and 0301 remove
// them, core still builds the modules that moved to surefold-integrations and here, and Gradle refuses to pick
// between two builds of one module; naming core's substitutions also stops Gradle guessing them.
fun modulesOf(dir: String): Set<String> =
    Regex("""module\("[^"]+", "([^"]+)"\)""").findAll(file("$dir/settings.gradle.kts").readText())
        .map { it.groupValues[1] }.toSet()

val upstreams = listOf("../surefold-core", "../surefold-integrations")
    .filter { file(it).resolve("settings.gradle.kts").exists() }
    .takeIf { providers.gradleProperty("surefold.upstreamSource").orNull != "false" }
    .orEmpty()
upstreams.forEach { dir ->
    if (dir == "../surefold-core") {
        val elsewhere = modules + upstreams.filter { it != dir }.flatMap(::modulesOf)
        includeBuild(dir) {
            dependencySubstitution {
                (modulesOf(dir) - elsewhere).forEach { substitute(module("dev.surefold:$it")).using(project(":$it")) }
            }
        }
    } else {
        includeBuild(dir)
    }
}
