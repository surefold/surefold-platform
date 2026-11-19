package dev.surefold.tenancy.crypto.gcp

import com.sun.net.httpserver.HttpServer
import dev.surefold.Shared
import dev.surefold.fail.Either
import dev.surefold.fail.right
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.tenancy.crypto.EncryptionContext
import dev.surefold.tenancy.crypto.ExpectedKeySet
import dev.surefold.tenancy.crypto.KeyFailureCode
import dev.surefold.tenancy.crypto.RootCreation
import dev.surefold.tenancy.crypto.RootExpectation
import dev.surefold.tenancy.crypto.RootKey
import dev.surefold.tenancy.crypto.RootLocation
import dev.surefold.tenancy.crypto.RootState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress
import java.net.URI
import java.time.Instant
import java.util.Base64
import java.util.zip.CRC32C

object GcpKeyOperationsSuite : Suite() {
    init {
        test("GcpKeyOperations wraps unwraps generates 32-byte data keys and verifies CRC32C and AAD") {
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            try {
                val storedAad = Shared("")
                val storedPlain = Shared(ByteArray(0))
                val keyPath =
                    "/v1/projects/doorlist-prod/locations/europe-west2/keyRings/doorlist-prod/cryptoKeys/platform-data"

                server.createContext("$keyPath:encrypt") { exchange ->
                    val body = Json.parseToJsonElement(exchange.requestBody.readAllBytes().decodeToString()).jsonObject
                    val plainB64 = body["plaintext"]!!.jsonPrimitive.content
                    val aadB64 = body["additionalAuthenticatedData"]!!.jsonPrimitive.content
                    val plain = Base64.getDecoder().decode(plainB64)
                    val aad = Base64.getDecoder().decode(aadB64)
                    val _ = storedPlain.update { plain }
                    val _ = storedAad.update { aad.decodeToString() }

                    val cipher = plain.reversedArray()
                    val cipherCrc = CRC32C().apply { update(cipher) }.value
                    val cipherB64 = Base64.getEncoder().encodeToString(cipher)
                    val response = """
                        {
                          "name": "${keyPath.removePrefix("/v1/")}/cryptoKeyVersions/1",
                          "ciphertext": "$cipherB64",
                          "ciphertextCrc32c": "$cipherCrc",
                          "verifiedPlaintextCrc32c": true,
                          "verifiedAdditionalAuthenticatedDataCrc32c": true
                        }
                    """.trimIndent().encodeToByteArray()
                    exchange.sendResponseHeaders(200, response.size.toLong())
                    exchange.responseBody.use { it.write(response) }
                }

                server.createContext("$keyPath:decrypt") { exchange ->
                    val body = Json.parseToJsonElement(exchange.requestBody.readAllBytes().decodeToString()).jsonObject
                    val cipherB64 = body["ciphertext"]!!.jsonPrimitive.content
                    val aadB64 = body["additionalAuthenticatedData"]!!.jsonPrimitive.content
                    val aad = Base64.getDecoder().decode(aadB64).decodeToString()
                    if (aad != storedAad.peek()) {
                        val err = """{"error":{"code":400,"message":"AAD mismatch"}}""".encodeToByteArray()
                        exchange.sendResponseHeaders(400, err.size.toLong())
                        exchange.responseBody.use { it.write(err) }
                    } else {
                        val cipher = Base64.getDecoder().decode(cipherB64)
                        val plain = cipher.reversedArray()
                        val plainCrc = CRC32C().apply { update(plain) }.value
                        val plainB64 = Base64.getEncoder().encodeToString(plain)
                        val response = """
                            {
                              "plaintext": "$plainB64",
                              "plaintextCrc32c": "$plainCrc"
                            }
                        """.trimIndent().encodeToByteArray()
                        exchange.sendResponseHeaders(200, response.size.toLong())
                        exchange.responseBody.use { it.write(response) }
                    }
                }

                server.createContext(keyPath) { exchange ->
                    val response = """
                        {
                          "name": "${keyPath.removePrefix("/v1/")}",
                          "primary": { "state": "ENABLED" },
                          "labels": { "env": "prod" }
                        }
                    """.trimIndent().encodeToByteArray()
                    exchange.sendResponseHeaders(200, response.size.toLong())
                    exchange.responseBody.use { it.write(response) }
                }

                server.start()
                val ops = GcpKeyOperations(
                    projectId = "doorlist-prod",
                    keyRing = "doorlist-prod",
                    endpoint = URI.create("http://127.0.0.1:${server.address.port}"),
                    tokens = { "test-token".right() },
                )
                val root = RootKey(keyPath.removePrefix("/v1/"))
                val ctx = EncryptionContext(mapOf("tenant_id" to "t-1", "purpose" to "enc", "version" to "1"))

                val generated = (ops.generateDataKey(root, ctx) as Either.Right).value
                generated.plaintext.size shouldBe 32
                storedAad.peek() shouldBe "purpose=enc&tenant_id=t-1&version=1"

                val unwrapped = (ops.unwrap(root, generated.wrapped, ctx) as Either.Right).value
                unwrapped.toList() shouldBe generated.plaintext.toList()

                val wrongCtx = EncryptionContext(mapOf("tenant_id" to "t-2", "purpose" to "enc", "version" to "1"))
                val failed = (ops.unwrap(root, generated.wrapped, wrongCtx) as Either.Left).value
                failed.code shouldBe KeyFailureCode.InvalidCiphertext

                val resolved = (ops.resolveAlias("europe-west2", "alias/platform-data") as Either.Right).value
                resolved.state shouldBe RootState.Enabled
                resolved.location.root shouldBe root
            } finally {
                server.stop(0)
            }
        }

        test("GcpKeyOperations creates primary keys binds aliases and schedules deletion") {
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            try {
                val basePath = "/v1/projects/doorlist-prod/locations/europe-west2/keyRings/doorlist-prod/cryptoKeys"
                server.createContext(basePath) { exchange ->
                    val path = exchange.requestURI.path
                    val response = when {
                        path.endsWith(":destroy") -> """{"destroyTime":"2030-01-01T00:00:00Z"}"""

                        path.endsWith("/cryptoKeyVersions") -> """
                            {"cryptoKeyVersions":[{"name":"${basePath.removePrefix("/v1/")}/mrk-proof/cryptoKeyVersions/1",
                            "state":"ENABLED"}]}
                        """.trimIndent()

                        else -> """
                            {
                              "name": "${basePath.removePrefix("/v1/")}/mrk-proof",
                              "primary": { "state": "ENABLED" },
                              "labels": { "owner": "t1" },
                              "destroyScheduledDuration": "604800s"
                            }
                        """.trimIndent()
                    }.encodeToByteArray()
                    exchange.sendResponseHeaders(200, response.size.toLong())
                    exchange.responseBody.use { it.write(response) }
                }
                server.start()
                val ops = GcpKeyOperations(
                    projectId = "doorlist-prod",
                    keyRing = "doorlist-prod",
                    endpoint = URI.create("http://127.0.0.1:${server.address.port}"),
                    tokens = { "test-token".right() },
                )
                val tags = mapOf("surefold:key-id" to "mrk-proof")
                val created = (
                    ops.createPrimary(
                        RootCreation("europe-west2", "policy", "desc", tags),
                    ) as Either.Right
                    ).value
                created.state shouldBe RootState.Enabled
                ops.bindAlias(created.location.root, "alias/doorlist/prod/t1") shouldBe Either.Right(Unit)
                ops.enableRotation(created.location.root) shouldBe Either.Right(Unit)
                val progress = ops.scheduleDeletion(
                    ExpectedKeySet(RootExpectation(created.location, mapOf("owner" to "t1")), emptyList()),
                    waitingDays = 7,
                )
                progress.failure shouldBe null
                progress.regions.size shouldBe 1
            } finally {
                server.stop(0)
            }
        }

        test("createPrimary names the key from surefold:key-id, labels it with the other tags and sets its wait") {
            StrictKms().use { kms ->
                val created = kms.operations(destroyScheduledDays = 30).createPrimary(
                    RootCreation(
                        "europe-west2", "gcp-iam", "desc",
                        mapOf(
                            "surefold:key-id" to "tenant-1",
                            "env" to "test",
                        ),
                    ),
                )
                (created as Either.Right).value.location.root shouldBe RootKey("$RING_NAME/cryptoKeys/tenant-1")
                val sent = kms.created.peek().single()
                sent["labels"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content } shouldBe mapOf("env" to "test")
                sent["destroyScheduledDuration"]!!.jsonPrimitive.content shouldBe "2592000s"
            }
        }

        test("createPrimary without a wait leaves Google's default, and a label Google refuses is InvalidInput") {
            StrictKms().use { kms ->
                val ops = kms.operations()
                ops.createPrimary(RootCreation("europe-west2", "gcp-iam", "desc", mapOf("surefold:key-id" to "k1")))
                kms.created.peek().single().containsKey("destroyScheduledDuration") shouldBe false
                val refused = ops.createPrimary(RootCreation("europe-west2", "gcp-iam", "desc", mapOf("a:b" to "c")))
                (refused as Either.Left).value.code shouldBe KeyFailureCode.InvalidInput
            }
        }

        test("scheduleDeletion destroys every live version across pages and keeps a date already accepted") {
            StrictKms(
                versions = listOf("ENABLED", "DESTROY_SCHEDULED", "DESTROYED", "DISABLED"),
                labels = mapOf("owner" to "t1"),
            ).use { kms ->
                val location = kms.location()
                val progress = kms.operations().scheduleDeletion(
                    ExpectedKeySet(RootExpectation(location, mapOf("owner" to "t1")), emptyList()),
                    waitingDays = 30,
                )
                progress.failure shouldBe null
                kms.destroyed.peek() shouldBe listOf("1", "4")
                progress.regions.single().effectiveAt shouldBe Instant.parse("2030-02-01T00:00:00Z")
            }
        }

        test("scheduleDeletion refuses a wait the key was not made with, or tags it does not carry, before any call") {
            StrictKms(versions = listOf("ENABLED"), labels = mapOf("owner" to "t1")).use { kms ->
                val ops = kms.operations()
                val owned = ExpectedKeySet(RootExpectation(kms.location(), mapOf("owner" to "t1")), emptyList())
                ops.scheduleDeletion(owned, waitingDays = 7).failure?.code shouldBe KeyFailureCode.Conflict
                val anothers = ExpectedKeySet(RootExpectation(kms.location(), mapOf("owner" to "t2")), emptyList())
                ops.scheduleDeletion(anothers, waitingDays = 30).failure?.code shouldBe KeyFailureCode.Conflict
                kms.destroyed.peek() shouldBe emptyList()
            }
        }

        test("scheduleDeletion answers no date when a version's is unreadable, and a failed destroy as its failure") {
            StrictKms(versions = listOf("ENABLED"), labels = emptyMap(), destroyTime = "soon").use { kms ->
                val expected = ExpectedKeySet(RootExpectation(kms.location(), emptyMap()), emptyList())
                kms.operations().scheduleDeletion(expected, waitingDays = 30).regions.single().effectiveAt shouldBe null
            }
            StrictKms(versions = listOf("ENABLED"), labels = emptyMap(), destroyStatus = 403).use { kms ->
                val expected = ExpectedKeySet(RootExpectation(kms.location(), emptyMap()), emptyList())
                val progress = kms.operations().scheduleDeletion(expected, waitingDays = 30)
                progress.failure?.code shouldBe KeyFailureCode.Refused
                progress.regions shouldBe emptyList()
            }
        }
    }
}

private const val RING_NAME = "projects/p1/locations/europe-west2/keyRings/r1"

/** Cloud KMS as Google documents it where the module's claims depend on it: label names, versions by page, the wait. */
private class StrictKms(
    versions: List<String> = emptyList(),
    private val labels: Map<String, String> = emptyMap(),
    private val destroyTime: String = "2030-02-01T00:00:00Z",
    private val destroyStatus: Int = 200,
) : AutoCloseable {
    val created = Shared(emptyList<JsonObject>())
    val destroyed = Shared(emptyList<String>())
    private val key = "$RING_NAME/cryptoKeys/k1"
    private val versions = versions.mapIndexed { index, state -> (index + 1).toString() to state }
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/v1/") { exchange ->
            val (status, body) = answer(
                exchange.requestMethod,
                exchange.requestURI.path.removePrefix("/v1/"),
                exchange.requestURI.query.orEmpty(),
                exchange.requestBody.readAllBytes().decodeToString(),
            )
            val bytes = body.encodeToByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        start()
    }

    fun operations(destroyScheduledDays: Int? = null) = GcpKeyOperations(
        projectId = "p1",
        keyRing = "r1",
        endpoint = URI.create("http://127.0.0.1:${server.address.port}"),
        tokens = { "test-token".right() },
        destroyScheduledDays = destroyScheduledDays,
    )

    fun location() = RootLocation(RootKey(key), "p1", "gcp", "europe-west2")

    private fun answer(method: String, path: String, query: String, body: String): Pair<Int, String> = when {
        method == "POST" && path == "$RING_NAME/cryptoKeys" -> create(query, Json.parseToJsonElement(body).jsonObject)

        method == "POST" && path.endsWith(":destroy") -> {
            val _ = destroyed.update { it + path.substringAfterLast('/').removeSuffix(":destroy") }
            destroyStatus to """{"destroyTime":"$destroyTime"}"""
        }

        path == "$key/cryptoKeyVersions" -> 200 to page(first = query.isEmpty())

        path == key -> {
            val pairs = labels.entries.joinToString(",") { (name, value) -> """"$name":"$value"""" }
            200 to """{"name":"$key","labels":{$pairs},"destroyScheduledDuration":"2592000s"}"""
        }

        else -> 404 to "{}"
    }

    private fun create(query: String, sent: JsonObject): Pair<Int, String> {
        val names = sent["labels"]!!.jsonObject.keys
        if (!names.all { Regex("[a-z][a-z0-9_-]{0,62}").matches(it) }) return 400 to "{}"
        val _ = created.update { it + sent }
        return 200 to """{"name":"$RING_NAME/cryptoKeys/${query.removePrefix("cryptoKeyId=")}"}"""
    }

    private fun page(first: Boolean): String {
        val items = (if (first) versions.take(2) else versions.drop(2)).joinToString(",") { (number, state) ->
            val scheduled = if (state == "DESTROY_SCHEDULED") ""","destroyTime":"2030-01-15T00:00:00Z"""" else ""
            """{"name":"$key/cryptoKeyVersions/$number","state":"$state"$scheduled}"""
        }
        val next = if (first && versions.size > 2) ""","nextPageToken":"more"""" else ""
        return """{"cryptoKeyVersions":[$items]$next}"""
    }

    override fun close() = server.stop(0)
}
