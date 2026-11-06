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
import dev.surefold.tenancy.crypto.RootState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress
import java.net.URI
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

                        else -> """
                            {
                              "name": "${basePath.removePrefix("/v1/")}/mrk-proof",
                              "primary": { "state": "ENABLED" },
                              "labels": { "surefold:key-id": "mrk-proof" }
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
                    ExpectedKeySet(RootExpectation(created.location, tags), emptyList()),
                    waitingDays = 7,
                )
                progress.failure shouldBe null
                progress.regions.size shouldBe 1
            } finally {
                server.stop(0)
            }
        }
    }
}
