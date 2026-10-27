package io.github.matthewjones372.surefold.tenancy

import io.github.matthewjones372.surefold.Carriers
import io.github.matthewjones372.surefold.fail.Either
import io.github.matthewjones372.surefold.fail.attempt
import io.github.matthewjones372.surefold.junit.Suite
import io.github.matthewjones372.surefold.junit.shouldBe
import io.github.matthewjones372.surefold.junit.shouldContainExactly
import io.github.matthewjones372.surefold.junit.shouldThrow
import io.github.matthewjones372.surefold.parMap

/** A tenant bound once, and seen by every fork and every message it sends (spec 0225). */
object TenantSuite : Suite() {
    init {
        test("a tenant bound before a parMap is read in every branch") {
            val seen = withTenant(TenantId("acme")) {
                (attempt<Nothing, List<TenantId?>> { parMap(1..20) { currentTenant() } } as Either.Right).value
            }
            seen.toSet() shouldBe setOf(TenantId("acme"))
        }

        test("outside any withTenant there is no tenant, and an inner one wins until it ends") {
            currentTenant() shouldBe null
            withTenant(TenantId("acme")) {
                withTenant(TenantId("globex")) { currentTenant() } shouldBe TenantId("globex")
                currentTenant() shouldBe TenantId("acme")
            }
        }

        test("the tenant rides what a message or a job carries, and is bound again where it lands") {
            val carried = withTenant(TenantId("acme")) { Carriers.capture() }
            carried shouldBe mapOf("surefold.tenant" to "acme")
            Carriers.within(carried) { currentTenant() } shouldBe TenantId("acme")
            Carriers.within(emptyMap()) { currentTenant() } shouldBe null
            Carriers.capture() shouldContainExactly emptyMap()
        }

        test("a blank tenant id is refused") {
            shouldThrow<IllegalArgumentException> { TenantId(" ") }
        }
    }
}
