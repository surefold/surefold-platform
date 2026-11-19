package dev.surefold.tenancy.crypto

import dev.surefold.fail.Either
import dev.surefold.junit.Suite
import dev.surefold.junit.expect
import dev.surefold.junit.shouldBe
import dev.surefold.resourceScope
import dev.surefold.tenancy.RlsDatabase
import dev.surefold.tenancy.TenantId
import dev.surefold.tenancy.TenantTransactions

object KeyCacheCompatibilitySuite : Suite() {
    init {
        test("the 0.18 key ring still returns independent data key copies") {
            val database = RlsDatabase(bookings = false).apply {
                apply("surefold/tenancy-crypto/postgres.sql")
                grantTo("surefold_tenant_keys")
            }
            val owner = KeyOwner.Tenant(TenantId("compatibility"))
            val purpose = KeyPurpose.Enc
            resourceScope {
                val cache = KeyCache()
                onRelease { _ -> cache.closeAndJoin() }
                val keys = TenantKeys(TenantTransactions(database.app, database.system), InMemoryKms(), cache)
                keys.provision(owner).answer()
                val ring: KeyRing = keys

                val original = ring.current(owner, purpose).answer()
                val expected = DataKey(original.owner, original.purpose, original.version, original.material.copyOf())
                Triple(expected.owner, expected.purpose, expected.version) shouldBe Triple(owner, purpose, 1)
                overwrite(ring.current(owner, purpose).answer())
                expect(sameMaterial(original, expected)) {
                    "overwriting a second current result must preserve the first copy"
                }
                expect(sameMaterial(ring.current(owner, purpose).answer(), expected)) {
                    "overwriting a current result must preserve the cached key"
                }

                keys.rotate(owner).answer()
                ring.current(owner, purpose).answer().version shouldBe 2
                ring.versions(owner, purpose).answer() shouldBe listOf(2, 1)
                val numbered = ring.version(owner, purpose, version = 1).answer()
                expect(sameMaterial(numbered, expected)) { "version 1 keeps its original material" }
                overwrite(ring.version(owner, purpose, version = 1).answer())
                expect(sameMaterial(numbered, expected)) {
                    "overwriting a second numbered result must preserve the first copy"
                }

                cache.evict(owner)
                cache.closeAndJoin()
                expect(sameMaterial(original, expected)) { "shutdown must preserve the current copy" }
                expect(sameMaterial(numbered, expected)) { "shutdown must preserve the numbered copy" }
            }
        }
    }

    // Keep the assertion's failure rendering at the DataKey boundary.
    private fun sameMaterial(key: DataKey, expected: DataKey): Boolean = key.material.contentEquals(expected.material)

    private fun overwrite(key: DataKey) {
        key.material.fill((key.material.first().toInt() xor 1).toByte())
    }

    private fun <A> Either<KeyError, A>.answer(): A = (this as Either.Right).value
}
