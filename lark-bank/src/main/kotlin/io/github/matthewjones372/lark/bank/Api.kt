package io.github.matthewjones372.lark.bank

import arrow.core.Either
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.Executors

/** What a request comes to: a status and a JSON body. */
internal data class Answer(val status: Int, val body: Map<String, Any>)

private val ids = Regex("[A-Za-z0-9_-]{1,64}")

private val pages = Regex("[a-z-]+\\.[a-z]+")

private val types = mapOf(
    "html" to "text/html; charset=utf-8",
    "js" to "text/javascript; charset=utf-8",
    "css" to "text/css; charset=utf-8",
)

private fun failed(status: Int, why: String) = Answer(status, mapOf("error" to why))

private fun <A> Either<*, A>.or(status: Int, answer: (A) -> Answer): Answer = fold({ failed(status, "$it") }, answer)

/**
 * The consumer's JSON API on one node, served by the JDK's `HttpServer` on [port], each exchange on a virtual thread
 * of its own, with the pages and the admin's stream of the node's events. [crash] ends a node in this process as a
 * crash would, for the admin's "crash" button. There is no library, so the routes are a
 * `when` over the method and the path's parts.
 */
internal class Api(
    private val node: BankNode,
    port: Int,
    streaming: Streaming = Streaming(),
    private val crash: (node: String) -> Boolean = { false },
) : AutoCloseable {
    private val server: HttpServer = HttpServer.create(InetSocketAddress(port), 0).apply {
        executor = Executors.newVirtualThreadPerTaskExecutor()
        createContext("/api/") { exchange -> exchange.use { respond(it, answer(it)) } }
        createContext("/") { exchange -> exchange.use(::page) }
        createContext("/admin/stream", streamOf(node.hub, streaming))
        start()
    }

    val port: Int get() = server.address.port

    private fun answer(exchange: HttpExchange): Answer {
        val path = exchange.requestURI.path.removePrefix("/api/").split("/")
        val posted = exchange.requestMethod == "POST"
        val body = if (posted) Json.read(exchange.requestBody.readAllBytes().decodeToString()) else null
        val got = exchange.requestMethod == "GET" && path.size == 2
        return when {
            posted && body == null -> failed(400, "the body is not a flat JSON object")
            posted && path == listOf("accounts") -> open(body.orEmpty())
            posted && path == listOf("transfers") -> transfer(body.orEmpty())
            posted && path == listOf("load") -> load(body.orEmpty())
            posted && path == listOf("crash") -> crash(body.orEmpty())
            got && path[0] == "accounts" -> account(path[1])
            got && path[0] == "transfers" -> status(path[1])
            else -> failed(404, "no route for ${exchange.requestMethod} ${exchange.requestURI.path}")
        }
    }

    /** A page or what it loads, from the jar's `web` resources; `/` is the consumer's page. */
    private fun page(exchange: HttpExchange) {
        val name = when (val path = exchange.requestURI.path.removePrefix("/")) {
            "" -> "index.html"
            "admin" -> "admin.html"
            else -> path
        }
        val type = types[name.substringAfterLast('.')]?.takeIf { pages.matches(name) }
        val bytes = type?.let { Api::class.java.getResource("/web/$name")?.readBytes() }
        if (bytes == null) respond(exchange, failed(404, "no page $name")) else respond(exchange, 200, type, bytes)
    }

    private fun open(body: Map<String, String>): Answer {
        val id =
            body["id"]?.takeIf(ids::matches) ?: return failed(400, "an account id is 1 to 64 letters, digits, - or _")
        val pence = body["amount"]?.toLongOrNull()?.takeIf { it >= 0 } ?: return failed(400, "amount is a whole number")
        return node.open(id, pence).or(503) { Answer(200, mapOf("id" to id, "balance" to it)) }
    }

    private fun account(id: String): Answer = node.statement(id).or(503) { statement ->
        if (!statement.open) return@or failed(404, "no account $id")
        val movements = statement.movements.map { mapOf("transfer" to it.transfer, "amount" to it.pence) }
        Answer(200, mapOf("id" to id, "balance" to statement.balance, "movements" to movements))
    }

    private fun transfer(body: Map<String, String>): Answer {
        val from = body["from"]?.takeIf(ids::matches) ?: return failed(400, "from names no account")
        val to = body["to"]?.takeIf { ids.matches(it) && it != from } ?: return failed(400, "to names no other account")
        val pence = body["amount"]?.toLongOrNull()?.takeIf { it > 0 } ?: return failed(400, "amount is more than 0")
        if (node.statement(to).getOrNull()?.open != true) return failed(404, "no account $to")
        val id = "t-${UUID.randomUUID()}"
        return node.transfer(id, from, to, pence).or(503) { Answer(202, mapOf("id" to id)) }
    }

    private fun load(body: Map<String, String>): Answer {
        val on = body["on"]?.toBooleanStrictOrNull() ?: return failed(400, "on is true or false")
        node.load(on)
        return Answer(202, mapOf("load" to on))
    }

    private fun crash(body: Map<String, String>): Answer {
        val name = body["node"].orEmpty()
        return if (crash(name)) Answer(202, mapOf("crashed" to name)) else failed(404, "no node $name in this process")
    }

    private fun status(id: String): Answer = node.status(id).or(503) { phase ->
        if (phase ==
            Phase.New.name
        ) failed(404, "no transfer $id") else Answer(200, mapOf("id" to id, "status" to phase))
    }

    private fun respond(exchange: HttpExchange, answer: Answer) =
        respond(exchange, answer.status, "application/json", Json.write(answer.body).encodeToByteArray())

    private fun respond(exchange: HttpExchange, status: Int, type: String, bytes: ByteArray) {
        exchange.responseHeaders.add("Content-Type", type)
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.write(bytes)
    }

    override fun close() = server.stop(0)
}
