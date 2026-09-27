package io.github.matthewjones372.lark.bank

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ApiTest {
    private val ended = LinkedBlockingQueue<String>()
    private val nodes = threeNodes { id, _ -> ended.put(id) }
    private val apis = nodes.map { Api(it, 0) }
    private val client = HttpClient.newHttpClient()

    private fun call(node: Int, method: String, path: String, body: String? = null): Pair<Int, String> {
        val publisher = body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody()
        val request = HttpRequest.newBuilder(URI("http://localhost:${apis[node].port}$path")).method(method, publisher)
        val response = client.send(request.build(), HttpResponse.BodyHandlers.ofString())
        return response.statusCode() to response.body()
    }

    /** Sends a transfer through [node], and waits until some node has heard it end; its id. */
    private fun transfer(node: Int, from: String, to: String, amount: Long): String {
        val (status, body) = call(node, "POST", "/api/transfers", """{"from":"$from","to":"$to","amount":$amount}""")
        status shouldBe 202
        val id = Json.read(body)?.get("id").orEmpty()
        generateSequence { ended.poll(30, TimeUnit.SECONDS) }.first { it == id }
        return id
    }

    @Test
    fun `two accounts opened, money moved between them through other nodes, and an overdraft refused`() {
        call(0, "POST", "/api/accounts", """{"id":"alice","amount":500}""") shouldBe
            (200 to """{"id":"alice","balance":500}""")
        call(1, "POST", "/api/accounts", """{"id":"bob","amount":20}""") shouldBe
            (200 to """{"id":"bob","balance":20}""")

        val paid = transfer(2, "alice", "bob", 120)
        call(0, "GET", "/api/transfers/$paid") shouldBe (200 to """{"id":"$paid","status":"Done"}""")
        call(1, "GET", "/api/accounts/alice") shouldBe
            (200 to """{"id":"alice","balance":380,"movements":[{"transfer":"$paid","amount":-120}]}""")
        call(2, "GET", "/api/accounts/bob") shouldBe
            (200 to """{"id":"bob","balance":140,"movements":[{"transfer":"$paid","amount":120}]}""")

        val overdrawn = transfer(0, "bob", "alice", 141)
        call(1, "GET", "/api/transfers/$overdrawn") shouldBe (200 to """{"id":"$overdrawn","status":"Refused"}""")
        call(2, "GET", "/api/accounts/bob").second shouldBe
            """{"id":"bob","balance":140,"movements":[{"transfer":"$paid","amount":120}]}"""
    }

    @Test
    fun `what the API cannot take is refused with a reason`() {
        call(0, "POST", "/api/accounts", """{"id":"carol","amount":10}""").first shouldBe 200
        call(0, "POST", "/api/accounts", "not json") shouldBe
            (400 to """{"error":"the body is not a flat JSON object"}""")
        call(0, "POST", "/api/accounts", """{"id":"a|b","amount":1}""").first shouldBe 400
        call(0, "POST", "/api/accounts", """{"id":"dave","amount":"lots"}""").first shouldBe 400
        call(1, "POST", "/api/transfers", """{"from":"carol","to":"carol","amount":1}""").first shouldBe 400
        call(1, "POST", "/api/transfers", """{"from":"carol","to":"nobody","amount":1}""").first shouldBe 404
        call(1, "POST", "/api/transfers", """{"from":"carol","to":"alice","amount":0}""").first shouldBe 400
        call(1, "POST", "/api/transfers", """{"to":"alice","amount":1}""").first shouldBe 400
        call(2, "GET", "/api/accounts/nobody").first shouldBe 404
        call(2, "GET", "/api/transfers/t-none").first shouldBe 404
        call(2, "DELETE", "/api/accounts/carol").first shouldBe 404
    }

    @AfterAll
    fun close() {
        apis.forEach(Api::close)
        nodes.forEach(BankNode::close)
    }
}
