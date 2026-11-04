package dev.surefold.openfeature.postgres.guide

import dev.openfeature.sdk.OpenFeatureAPI
import dev.surefold.junit.Suite
import dev.surefold.junit.expect
import dev.surefold.junit.shouldBe
import dev.surefold.tenancy.RlsDatabase
import dev.surefold.tenancy.TenantId
import java.io.File

/** docs/flags-per-tenant.md's example is this package's GuideExample.kt, and a tenant it pauses is paused alone. */
object GuideSuite : Suite() {
    private val marked = Regex("""<!-- flags-postgres -->\s*```kotlin\n(.*?)\n```""", RegexOption.DOT_MATCHES_ALL)

    init {
        test("the guide's example is GuideExample.kt from its imports down") {
            val root = System.getProperty("surefold.openfeature.postgres.repoRoot")
            expect(root != null) { "the build must pass -Dsurefold.openfeature.postgres.repoRoot; see its build file" }
            val fences = marked.findAll(File(root!!, "docs/flags-per-tenant.md").readText()).map { it.groupValues[1] }
            val source = File(root, EXAMPLE).readText()
            fences.toList().map { it.trim() } shouldBe listOf(source.substring(source.indexOf("import ")).trim())
        }

        test("a tenant the example pauses reads paused, and another tenant does not") {
            val database = RlsDatabase(bookings = false).also { database ->
                database.apply("surefold/openfeature-postgres/postgres.sql")
                // The grants the page gives: reads to the application, writes to the role that bypasses the policy.
                database.execute("grant select on surefold_feature_flags to surefold_app")
                database.execute("grant select, insert, update, delete on surefold_feature_flags to surefold_system")
            }
            val (provider, _) = flags(database.app, database.owner)
            try {
                pause(provider, database.system, TenantId("acme"), operator = "ops:alice")

                paused(TenantId("acme")) shouldBe true
                paused(TenantId("globex")) shouldBe false
            } finally {
                OpenFeatureAPI.getInstance().shutdown()
            }
        }
    }

    private const val EXAMPLE =
        "data/surefold-openfeature-postgres/src/test/kotlin/" +
            "dev/surefold/openfeature/postgres/guide/GuideExample.kt"
}
