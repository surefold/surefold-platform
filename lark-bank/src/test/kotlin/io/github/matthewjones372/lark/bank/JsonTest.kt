package io.github.matthewjones372.lark.bank

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class JsonTest {

    @Test
    fun `an object is written with its strings escaped, its numbers bare and its lists and maps nested`() {
        val written = Json.write(
            mapOf("id" to "a \"b\"\\\n", "n" to -3, "ok" to true, "xs" to listOf(mapOf("k" to 1L), "s")),
        )

        written shouldBe """{"id":"a \"b\"\\\u000a","n":-3,"ok":true,"xs":[{"k":1},"s"]}"""
    }

    @Test
    fun `a flat object is read back, and anything else is not an object`() {
        Json.read(" { \"from\" : \"a\\\"\\u0041\\/\" , \"amount\": 12, \"ok\":true } ") shouldBe
            mapOf("from" to "a\"A/", "amount" to "12", "ok" to "true")
        Json.read("{}") shouldBe emptyMap()
        val broken = listOf("", "[]", "{", """{"a"}""", """{"a":}""", """{"a":1,}""", """{"a":1} x""", """{"a":[1]}""")
        (broken + """{"a":"\q"}""").forEach { Json.read(it) shouldBe null }
    }
}
