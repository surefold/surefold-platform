package dev.surefold.tenancy.crypto

import dev.surefold.fail.Either
import dev.surefold.fail.flatMap
import dev.surefold.junit.Suite
import dev.surefold.junit.expect
import dev.surefold.junit.shouldBe
import dev.surefold.resourceScope
import dev.surefold.tenancy.RlsDatabase
import dev.surefold.tenancy.TenantId
import dev.surefold.tenancy.TenantTransactions
import java.io.File

object KeyCacheCompatibilitySuite : Suite() {
    private val guideFence = Regex(
        """<!-- retained-key-views -->\s*```kotlin\n(.*?)\n```""",
        RegexOption.DOT_MATCHES_ALL,
    )

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

        test("the retained key view guide matches the compiled example") {
            val guide = configuredFile("surefold.tenancy.crypto.keyViewGuide").readText()
            val fence = guideFence.findAll(guide).single().groupValues[1]
            val (imports, declarations) = fence.split("\n\n", limit = 2)
            imports shouldBe """
                import dev.surefold.fail.Either
                import dev.surefold.fail.flatMap
                import dev.surefold.resourceScope
                import dev.surefold.tenancy.crypto.DataKey
                import dev.surefold.tenancy.crypto.KeyCache
                import dev.surefold.tenancy.crypto.KeyError
                import dev.surefold.tenancy.crypto.KeyIdentity
            """.trimIndent()

            val compiled = configuredFile("surefold.tenancy.crypto.keyViewExample").readText()
                .substringAfter("// retained-key-views\n")
                .substringBefore("\n// end retained-key-views")
            declarations shouldBe compiled
        }

        test("the guide uses a retained key and joins the cache at scope exit") {
            val identity = KeyIdentity(KeyOwner.Platform, KeyPurpose.Enc, 1, byteArrayOf(1))
            val cache = withRetainedKeyCache { running ->
                val result = useRetainedKey(
                    running,
                    identity,
                    load = { Either.Right(DataKey(identity.owner, identity.purpose, identity.version, ByteArray(32))) },
                    operation = { it.size },
                )
                result shouldBe Either.Right(32)
                running
            }
            cache.lease(identity) { error("the joined cache must refuse the loader") } shouldBe
                Either.Left(KeyError.Denied("the key view is unavailable"))
        }
    }

    private fun configuredFile(property: String) =
        File(checkNotNull(System.getProperty(property)) { "the build must provide $property" })

    // Keep the assertion's failure rendering at the DataKey boundary.
    private fun sameMaterial(key: DataKey, expected: DataKey): Boolean = key.material.contentEquals(expected.material)

    private fun overwrite(key: DataKey) {
        key.material.fill((key.material.first().toInt() xor 1).toByte())
    }

    private fun <A> Either<KeyError, A>.answer(): A = (this as Either.Right).value
}

// retained-key-views
private fun <A> withRetainedKeyCache(work: (KeyCache) -> A): A = resourceScope {
    val cache = KeyCache()
    onRelease { _ -> cache.closeAndJoin() }
    work(cache)
}

private fun <A> useRetainedKey(
    cache: KeyCache,
    identity: KeyIdentity,
    load: () -> Either<KeyError, DataKey>,
    operation: (ByteArray) -> A,
): Either<KeyError, A> = cache.lease(identity, load).flatMap { view ->
    resourceScope {
        onRelease { _ -> view.close() }
        view.using(operation)
    }
}
// end retained-key-views
