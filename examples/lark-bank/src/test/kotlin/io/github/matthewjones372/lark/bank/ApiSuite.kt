package io.github.matthewjones372.lark.bank

import io.github.matthewjones372.lark.Resource
import io.github.matthewjones372.lark.junit.Expectations
import io.github.matthewjones372.lark.junit.SharedSuite
import io.github.matthewjones372.lark.junit.shouldBe
import io.github.matthewjones372.lark.resource
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Three nodes' APIs, and the transfers some node has heard end. */
internal class ApiCluster(private val ended: LinkedBlockingQueue<String>, private val apis: List<Api>) {
    private val client = HttpClient.newHttpClient()

    fun call(node: Int, method: String, path: String, body: String? = null): Pair<Int, String> {
        val publisher = body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody()
        val request = HttpRequest.newBuilder(URI("http://localhost:${apis[node].port}$path")).method(method, publisher)
        val response = client.send(request.build(), HttpResponse.BodyHandlers.ofString())
        return response.statusCode() to response.body()
    }

    /**
     * Sends a transfer through [node], and waits until some node has heard it end; whether it was taken, and its id.
     */
    fun transfer(node: Int, from: String, to: String, amount: Long): Pair<Expectations, String> {
        val (status, body) = call(node, "POST", "/api/transfers", """{"from":"$from","to":"$to","amount":$amount}""")
        val accepted = status shouldBe 202
        val id = json.parseToJsonElement(body).jsonObject.getValue("id").jsonPrimitive.content
        val _ = generateSequence { ended.poll(30, TimeUnit.SECONDS) }.first { it == id }
        return accepted to id
    }
}

private fun apiCluster(): Resource<ApiCluster> = resource {
    val ended = LinkedBlockingQueue<String>()
    val nodes = install({ threeNodes { id, _ -> ended.put(id) } }) { nodes, _ -> nodes.forEach(BankNode::close) }
    val apis = install({ nodes.map { Api(it, 0) } }) { apis, _ -> apis.forEach(Api::close) }
    ApiCluster(ended, apis)
}

internal object ApiSuite : SharedSuite<ApiCluster>() {
    override val shared: Resource<ApiCluster> = apiCluster()

    init {
        test("two accounts opened, money moved between them through other nodes, and an overdraft refused") { bank ->
            val alice = bank.call(0, "POST", "/api/accounts", """{"id":"alice","amount":500}""") shouldBe
                (200 to """{"id":"alice","balance":500}""")
            val bob = bank.call(1, "POST", "/api/accounts", """{"id":"bob","amount":20}""") shouldBe
                (200 to """{"id":"bob","balance":20}""")

            val (paidTaken, paid) = bank.transfer(2, "alice", "bob", 120)
            val done = bank.call(0, "GET", "/api/transfers/$paid") shouldBe
                (200 to """{"id":"$paid","status":"Done"}""")
            val debited = bank.call(1, "GET", "/api/accounts/alice") shouldBe
                (200 to """{"id":"alice","balance":380,"movements":[{"transfer":"$paid","amount":-120}]}""")
            val credited = bank.call(2, "GET", "/api/accounts/bob") shouldBe
                (200 to """{"id":"bob","balance":140,"movements":[{"transfer":"$paid","amount":120}]}""")

            val (overdrawnTaken, overdrawn) = bank.transfer(0, "bob", "alice", 141)
            val refused = bank.call(1, "GET", "/api/transfers/$overdrawn") shouldBe
                (200 to """{"id":"$overdrawn","status":"Refused"}""")
            val unmoved = bank.call(2, "GET", "/api/accounts/bob").second shouldBe
                """{"id":"bob","balance":140,"movements":[{"transfer":"$paid","amount":120}]}"""

            alice and bob and paidTaken and done and debited and credited and overdrawnTaken and refused and unmoved
        }

        test("what the API cannot take is refused with a reason") { bank ->
            fun status(node: Int, method: String, path: String, body: String? = null) =
                bank.call(node, method, path, body).first
            (status(0, "POST", "/api/accounts", """{"id":"carol","amount":10}""") shouldBe 200) and
                (
                    bank.call(0, "POST", "/api/accounts", "not json") shouldBe
                        (400 to """{"error":"the body is not an account"}""")
                    ) and
                (status(0, "POST", "/api/accounts", """{"id":"a|b","amount":1}""") shouldBe 400) and
                (status(0, "POST", "/api/accounts", """{"id":"dave","amount":"lots"}""") shouldBe 400) and
                (
                    bank.call(0, "POST", "/api/accounts", """{"id":"dave","amount":1.5}""") shouldBe
                        (400 to """{"error":"amount is a whole number"}""")
                    ) and
                (
                    bank.call(0, "POST", "/api/load", """{"on":"yes"}""") shouldBe
                        (400 to """{"error":"on is true or false"}""")
                    ) and
                (
                    bank.call(0, "POST", "/api/crash", "{}") shouldBe
                        (404 to """{"error":"no node  in this process"}""")
                    ) and
                (status(1, "POST", "/api/transfers", """{"from":"carol","to":"carol","amount":1}""") shouldBe 400) and
                (status(1, "POST", "/api/transfers", """{"from":"carol","to":"nobody","amount":1}""") shouldBe 404) and
                (status(1, "POST", "/api/transfers", """{"from":"carol","to":"alice","amount":0}""") shouldBe 400) and
                (status(1, "POST", "/api/transfers", """{"to":"alice","amount":1}""") shouldBe 400) and
                (status(2, "GET", "/api/accounts/nobody") shouldBe 404) and
                (status(2, "GET", "/api/transfers/t-none") shouldBe 404) and
                (status(2, "DELETE", "/api/accounts/carol") shouldBe 404)
        }
    }
}
