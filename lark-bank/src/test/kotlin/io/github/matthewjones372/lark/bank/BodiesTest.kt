package io.github.matthewjones372.lark.bank

import io.github.matthewjones372.lark.assert.shouldBe
import org.junit.jupiter.api.Test

class BodiesTest {

    @Test
    fun `a body is read with fields it does not know ignored, and anything that is not an object is not a body`() {
        decoded<CrashBody>("""{"node":"n2","extra":[1]}""") shouldBe CrashBody("n2")
        val broken = listOf("", "[]", "{", """{"a"}""", """{"a":}""", """{"a":1,}""", """{"a":1} x""", """{"a":"\q"}""")
        broken.forEach { decoded<CrashBody>(it) shouldBe null }
    }

    @Test
    fun `a missing field still reads, so its route can say what is wrong`() {
        decoded<OpenBody>("{}") shouldBe OpenBody()
        decoded<CrashBody>("{}") shouldBe CrashBody("")
        decoded<LoadBody>("{}")?.on.flag() shouldBe null
    }

    @Test
    fun `an amount is a whole number written bare or as a string, and nothing else`() {
        fun amount(written: String) = decoded<TransferBody>("""{"amount":$written}""")?.amount.whole()
        amount("12") shouldBe 12L
        amount("\"12\"") shouldBe 12L
        amount("-3") shouldBe -3L
        listOf("1.5", "\"x\"", "null", "true").forEach { amount(it) shouldBe null }
        decoded<TransferBody>("""{"amount":{}}""") shouldBe null
    }

    @Test
    fun `on is true or false written bare or as a string`() {
        fun on(written: String) = decoded<LoadBody>("""{"on":$written}""")?.on.flag()
        on("true") shouldBe true
        on("\"false\"") shouldBe false
        listOf("1", "\"yes\"", "null").forEach { on(it) shouldBe null }
    }
}
