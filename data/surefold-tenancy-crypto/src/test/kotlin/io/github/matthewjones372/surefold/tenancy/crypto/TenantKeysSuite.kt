package io.github.matthewjones372.surefold.tenancy.crypto

import io.github.matthewjones372.surefold.fail.Either
import io.github.matthewjones372.surefold.junit.Suite
import io.github.matthewjones372.surefold.junit.shouldBe
import io.github.matthewjones372.surefold.junit.shouldBeInstanceOf
import io.github.matthewjones372.surefold.tenancy.RlsDatabase
import io.github.matthewjones372.surefold.tenancy.TenantId
import io.github.matthewjones372.surefold.tenancy.TenantTransactions
import io.github.matthewjones372.surefold.tenancy.withTenant

/** Every owner's keys in Postgres, under its own root, and one tenant's rows out of another's reach (spec 0235). */
object TenantKeysSuite : Suite() {

    private val acme = KeyOwner.Tenant(TenantId("acme"))
    private val globex = KeyOwner.Tenant(TenantId("globex"))

    private class Fixture {
        val database = RlsDatabase(bookings = false).apply {
            apply("surefold/tenancy-crypto/postgres.sql")
            grantTo("surefold_tenant_keys")
        }
        val tenants = TenantTransactions(database.app, database.system)
        val keys = TenantKeys(tenants, InMemoryKms())
    }

    private fun <A> Either<KeyError, A>.held(): A = (this as Either.Right).value

    private fun DataKey.bytes() = material.toList()

    init {
        test("rotation makes version 2 current, and version 1 still opens what it wrote") {
            val keys = Fixture().keys
            keys.provision(acme).held()
            val first = keys.current(acme, KeyPurpose.Enc).held()

            keys.rotate(acme).held()

            val second = keys.current(acme, KeyPurpose.Enc).held()
            (first.version to second.version) shouldBe (1 to 2)
            keys.version(acme, KeyPurpose.Enc, 1).held().bytes() shouldBe first.bytes()
            (second.bytes() == first.bytes()) shouldBe false
            keys.current(acme, KeyPurpose.Bidx).held().version shouldBe 2
        }

        test("tenant A's transaction reads none of B's key rows") {
            val held = Fixture()
            held.keys.provision(acme).held()
            held.keys.provision(globex).held()

            val seen = held.tenants.transaction(TenantId("acme")) { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("select distinct tenant_id from surefold_tenant_keys").use { rows ->
                        generateSequence { if (rows.next()) rows.getString(1) else null }.toList()
                    }
                }
            }
            seen shouldBe listOf("acme")
        }

        test("the bound tenant's key is the one answered, and none is answered with no tenant bound") {
            val keys = Fixture().keys
            keys.provision(acme).held()

            withTenant(TenantId("acme")) { keys.current(KeyPurpose.Enc).held().owner } shouldBe acme
            keys.current(KeyPurpose.Enc) shouldBe Either.Left(KeyError.NoTenant)
        }

        test("a second provision changes nothing, and an owner not provisioned has nothing to rotate") {
            val keys = Fixture().keys
            keys.provision(acme).held()
            val first = keys.current(acme, KeyPurpose.Enc).held()

            keys.provision(acme).held()

            keys.current(acme, KeyPurpose.Enc).held().bytes() shouldBe first.bytes()
            keys.rotate(globex).shouldBeInstanceOf<Either.Left<KeyError.Invalid>>()
        }

        test("a retired version is gone, and the active one cannot be retired") {
            val keys = Fixture().keys
            keys.provision(acme).held()
            keys.rotate(acme).held()

            keys.retire(acme, KeyPurpose.Enc, 1).held()

            keys.version(acme, KeyPurpose.Enc, 1).shouldBeInstanceOf<Either.Left<KeyError.Invalid>>()
            keys.retire(acme, KeyPurpose.Enc, 2).shouldBeInstanceOf<Either.Left<KeyError.Invalid>>()
            keys.version(acme, KeyPurpose.Bidx, 1).held().version shouldBe 1
        }

        test("the platform's keys are kept beside the tenants', and out of every tenant's transaction") {
            val held = Fixture()
            held.keys.provision(KeyOwner.Platform).held()

            held.keys.current(KeyOwner.Platform, KeyPurpose.Enc).held().owner shouldBe KeyOwner.Platform
            held.tenants.transaction(TenantId("acme")) { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("select count(*) from surefold_tenant_keys").use { rows ->
                        rows.next()
                        rows.getInt(1)
                    }
                }
            } shouldBe 0
        }

        test("a KMS that cannot be reached leaves no key row behind") {
            val database = RlsDatabase(bookings = false).apply {
                apply("surefold/tenancy-crypto/postgres.sql")
                grantTo("surefold_tenant_keys")
            }
            val kms = InMemoryKms().apply { unavailable = true }
            val keys = TenantKeys(TenantTransactions(database.app, database.system), kms)

            keys.provision(acme).shouldBeInstanceOf<Either.Left<KeyError.Unavailable>>()

            kms.unavailable = false
            keys.current(acme, KeyPurpose.Enc).shouldBeInstanceOf<Either.Left<KeyError.Invalid>>()
        }
    }
}
