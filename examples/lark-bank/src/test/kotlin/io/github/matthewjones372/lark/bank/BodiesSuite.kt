package io.github.matthewjones372.lark.bank

import io.github.matthewjones372.lark.Resource
import io.github.matthewjones372.lark.junit.Suite
import io.github.matthewjones372.lark.junit.forAll
import io.github.matthewjones372.lark.junit.shouldBe
import io.github.matthewjones372.lark.resource

object BodiesSuite : Suite<Unit>() {
    override val shared: Resource<Unit> = resource { }

    init {
        test(
            "a body is read with fields it does not know ignored, and anything that is not an object is not a body",
        ) { _ ->
            val known = decoded<CrashBody>("""{"node":"n2","extra":[1]}""") shouldBe CrashBody("n2")
            val broken =
                listOf("", "[]", "{", """{"a"}""", """{"a":}""", """{"a":1,}""", """{"a":1} x""", """{"a":"\q"}""")
            known and broken.forAll { decoded<CrashBody>(it) shouldBe null }
        }

        test("a missing field still reads, so its route can say what is wrong") { _ ->
            (decoded<OpenBody>("{}") shouldBe OpenBody()) and
                (decoded<CrashBody>("{}") shouldBe CrashBody("")) and
                (decoded<LoadBody>("{}")?.on.flag() shouldBe null)
        }

        test("an amount is a whole number written bare or as a string, and nothing else") { _ ->
            fun amount(written: String) = decoded<TransferBody>("""{"amount":$written}""")?.amount.whole()
            (amount("12") shouldBe 12L) and
                (amount("\"12\"") shouldBe 12L) and
                (amount("-3") shouldBe -3L) and
                listOf("1.5", "\"x\"", "null", "true").forAll { amount(it) shouldBe null } and
                (decoded<TransferBody>("""{"amount":{}}""") shouldBe null)
        }

        test("on is true or false written bare or as a string") { _ ->
            fun on(written: String) = decoded<LoadBody>("""{"on":$written}""")?.on.flag()
            (on("true") shouldBe true) and
                (on("\"false\"") shouldBe false) and
                listOf("1", "\"yes\"", "null").forAll { on(it) shouldBe null }
        }
    }
}
