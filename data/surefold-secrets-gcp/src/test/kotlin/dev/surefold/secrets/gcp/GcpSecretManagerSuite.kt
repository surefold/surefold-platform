package dev.surefold.secrets.gcp

import com.sun.net.httpserver.HttpServer
import dev.surefold.fail.Either
import dev.surefold.fail.right
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.secrets.SecretError
import dev.surefold.secrets.SecretValue
import java.net.InetSocketAddress
import java.net.URI
import java.util.Base64
import java.util.zip.CRC32C

object GcpSecretManagerSuite : Suite() {
    init {
        test("GcpSecretManager reads secrets verifies CRC32C and maps parameter paths") {
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            try {
                val secretBytes = """{"username":"doorlist_app","password":"secret-password"}""".encodeToByteArray()
                val crc = CRC32C().apply { update(secretBytes) }.value
                val encoded = Base64.getEncoder().encodeToString(secretBytes)

                val tokenBytes = "tok-live-123".encodeToByteArray()
                val tokenCrc = CRC32C().apply { update(tokenBytes) }.value
                val tokenEncoded = Base64.getEncoder().encodeToString(tokenBytes)

                server.createContext("/v1/projects/doorlist-prod/secrets/db-app/versions/latest:access") { exchange ->
                    val json = """
                        {
                          "name": "projects/doorlist-prod/secrets/db-app/versions/3",
                          "payload": {
                            "data": "$encoded",
                            "dataCrc32c": "$crc"
                          }
                        }
                    """.trimIndent().encodeToByteArray()
                    exchange.sendResponseHeaders(200, json.size.toLong())
                    exchange.responseBody.use { it.write(json) }
                }

                server.createContext("/v1/projects/doorlist-prod/secrets/corrupt/versions/latest:access") { exchange ->
                    val json = """
                        {
                          "name": "projects/doorlist-prod/secrets/corrupt/versions/1",
                          "payload": {
                            "data": "$encoded",
                            "dataCrc32c": "999999"
                          }
                        }
                    """.trimIndent().encodeToByteArray()
                    exchange.sendResponseHeaders(200, json.size.toLong())
                    exchange.responseBody.use { it.write(json) }
                }

                server.createContext(
                    "/v1/projects/doorlist-prod/secrets/doorlist-prod-token-key/versions/latest:access",
                ) { exchange ->
                    val json = """
                        {
                          "name": "projects/doorlist-prod/secrets/doorlist-prod-token-key/versions/1",
                          "payload": {
                            "data": "$tokenEncoded",
                            "dataCrc32c": "$tokenCrc"
                          }
                        }
                    """.trimIndent().encodeToByteArray()
                    exchange.sendResponseHeaders(200, json.size.toLong())
                    exchange.responseBody.use { it.write(json) }
                }

                server.createContext("/v1/projects/doorlist-prod/secrets") { exchange ->
                    if (exchange.requestURI.path.endsWith("/secrets")) {
                        val json = """
                            {
                              "secrets": [
                                { "name": "projects/doorlist-prod/secrets/doorlist-prod-token-key" }
                              ]
                            }
                        """.trimIndent().encodeToByteArray()
                        exchange.sendResponseHeaders(200, json.size.toLong())
                        exchange.responseBody.use { it.write(json) }
                    } else {
                        exchange.sendResponseHeaders(404, -1)
                        exchange.close()
                    }
                }

                server.start()
                val baseUri = URI.create("http://127.0.0.1:${server.address.port}")
                val manager = GcpSecretManager(
                    projectId = "doorlist-prod",
                    endpoint = baseUri,
                    tokens = { "test-bearer-token".right() },
                )

                manager.getSecret("db-app") shouldBe Either.Right(
                    SecretValue("db-app", "3", secretBytes.decodeToString()),
                )
                manager.getSecret("db-app", "AWSPENDING") shouldBe Either.Left(SecretError.NotFound("db-app"))
                manager.getSecret("corrupt") shouldBe Either.Left(
                    SecretError.Unavailable("Secret Manager CRC32C mismatch for corrupt"),
                )
                manager.getParameter("/doorlist/prod/token-key") shouldBe Either.Right("tok-live-123")
                manager.getParameter("/doorlist/prod/missing") shouldBe
                    Either.Left(SecretError.NotFound("/doorlist/prod/missing"))
                manager.getParametersByPath("/doorlist/prod/") shouldBe Either.Right(
                    mapOf("/doorlist/prod/token-key" to "tok-live-123"),
                )
            } finally {
                server.stop(0)
            }
        }
    }
}
