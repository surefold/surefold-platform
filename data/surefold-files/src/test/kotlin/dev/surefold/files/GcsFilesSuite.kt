package dev.surefold.files

import com.sun.net.httpserver.HttpServer
import dev.surefold.Shared
import dev.surefold.fail.Either
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import java.io.ByteArrayInputStream
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.time.Duration.Companion.minutes

object GcsFilesSuite : Suite() {
    init {
        test("GcsFiles satisfies FilesContract and supports putIfAbsent and getVersion") {
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            try {
                data class StoredObj(
                    val key: String,
                    val bytes: ByteArray,
                    val contentType: String,
                    val generation: String,
                    val etag: String,
                )

                val objects = Shared(sortedMapOf<String, List<StoredObj>>())
                val genCounter = Shared(0L)

                server.createContext("/upload/storage/v1/b/test-bucket/o") { exchange ->
                    val query = parseQuery(exchange.requestURI.rawQuery.orEmpty())
                    val name = query["name"].orEmpty()
                    val ifGenMatch = query["ifGenerationMatch"]
                    val existing = objects.peek()[name].orEmpty()
                    if (ifGenMatch == "0" && existing.isNotEmpty()) {
                        exchange.sendResponseHeaders(412, -1)
                        exchange.close()
                    } else {
                        val bytes = exchange.requestBody.readAllBytes()
                        val contentType = exchange.requestHeaders.getFirst("Content-Type") ?: "application/octet-stream"
                        val _ = genCounter.update { it + 1 }
                        val gen = genCounter.peek().toString()
                        val etag = HexFormat.of().formatHex(
                            MessageDigest.getInstance("MD5").digest(bytes),
                        )
                        val obj = StoredObj(name, bytes, contentType, gen, etag)
                        val _ = objects.update { map ->
                            (map + (name to (existing + obj))).toSortedMap()
                        }
                        val json = """
                            {
                              "name": "$name",
                              "size": "${bytes.size}",
                              "contentType": "$contentType",
                              "generation": "$gen",
                              "etag": "$etag",
                              "updated": "2026-11-06T12:00:00Z"
                            }
                        """.trimIndent().encodeToByteArray()
                        exchange.sendResponseHeaders(200, json.size.toLong())
                        exchange.responseBody.use { it.write(json) }
                    }
                }

                server.createContext("/storage/v1/b/test-bucket/o") { exchange ->
                    val rawPath = exchange.requestURI.rawPath
                    val query = parseQuery(exchange.requestURI.rawQuery.orEmpty())
                    val afterPrefix = rawPath.removePrefix("/storage/v1/b/test-bucket/o")
                    when {
                        afterPrefix.isEmpty() -> {
                            val prefix = query["prefix"].orEmpty()
                            val maxResults = query["maxResults"]?.toIntOrNull() ?: 1000
                            val pageToken = query["pageToken"]
                            val matching = objects.peek().entries
                                .filter { it.key.startsWith(prefix) && it.value.isNotEmpty() }
                                .filter { pageToken == null || it.key > pageToken }
                            val page = matching.take(maxResults)
                            val next = if (matching.size > maxResults) page.last().key else null
                            val itemsJson = page.joinToString(",") { (k, versions) ->
                                val latest = versions.last()
                                """{"name":"$k","size":"${latest.bytes.size}","etag":"${latest.etag}","updated":"2026-11-06T12:00:00Z"}"""
                            }
                            val nextField = if (next == null) "" else """"nextPageToken":"$next","""
                            val json = """{$nextField"items":[$itemsJson]}""".encodeToByteArray()
                            exchange.sendResponseHeaders(200, json.size.toLong())
                            exchange.responseBody.use { it.write(json) }
                        }

                        "/rewriteTo/b/test-bucket/o/" in afterPrefix -> {
                            val fromKey = URLDecoder.decode(
                                afterPrefix.removePrefix("/").substringBefore("/rewriteTo/"),
                                StandardCharsets.UTF_8,
                            )
                            val toKey = URLDecoder.decode(
                                afterPrefix.substringAfter("/rewriteTo/b/test-bucket/o/"),
                                StandardCharsets.UTF_8,
                            )
                            val source = objects.peek()[fromKey]?.lastOrNull()
                            if (source == null) {
                                exchange.sendResponseHeaders(404, -1)
                                exchange.close()
                            } else {
                                val _ = genCounter.update { it + 1 }
                                val gen = genCounter.peek().toString()
                                val copied = source.copy(key = toKey, generation = gen)
                                val _ = objects.update { map ->
                                    (map + (toKey to listOf(copied))).toSortedMap()
                                }
                                val json = """{"done":true}""".encodeToByteArray()
                                exchange.sendResponseHeaders(200, json.size.toLong())
                                exchange.responseBody.use { it.write(json) }
                            }
                        }

                        else -> {
                            val key = URLDecoder.decode(afterPrefix.removePrefix("/"), StandardCharsets.UTF_8)
                            if (exchange.requestMethod == "DELETE") {
                                val _ = objects.update { map -> (map - key).toSortedMap() }
                                exchange.sendResponseHeaders(204, -1)
                                exchange.close()
                            } else {
                                val versions = objects.peek()[key].orEmpty()
                                val gen = query["generation"]
                                val found = if (gen == null) {
                                    versions.lastOrNull()
                                } else {
                                    versions.firstOrNull { it.generation == gen }
                                }
                                if (found == null) {
                                    exchange.sendResponseHeaders(404, -1)
                                    exchange.close()
                                } else if (query["alt"] == "media") {
                                    exchange.sendResponseHeaders(200, found.bytes.size.toLong())
                                    exchange.responseBody.use { it.write(found.bytes) }
                                } else {
                                    val json = """
                                        {
                                          "name": "${found.key}",
                                          "size": "${found.bytes.size}",
                                          "contentType": "${found.contentType}",
                                          "generation": "${found.generation}",
                                          "etag": "${found.etag}",
                                          "updated": "2026-11-06T12:00:00Z"
                                        }
                                    """.trimIndent().encodeToByteArray()
                                    exchange.sendResponseHeaders(200, json.size.toLong())
                                    exchange.responseBody.use { it.write(json) }
                                }
                            }
                        }
                    }
                }

                server.start()
                val baseUri = URI.create("http://127.0.0.1:${server.address.port}")
                val gcs = GcsFiles(
                    GcsFilesConfig(
                        bucket = "test-bucket",
                        endpoint = baseUri,
                        maxSize = 1024,
                    ),
                )

                val key = FileKey.of("archive/control.json").orThrow()
                val v1 = (
                    gcs.putIfAbsent(
                        key,
                        ByteArrayInputStream("v1".encodeToByteArray()),
                        2,
                        "application/json",
                    ) as Either.Right
                    ).value
                v1.size shouldBe 2L

                val duplicate = gcs.putIfAbsent(
                    key,
                    ByteArrayInputStream("v2".encodeToByteArray()),
                    2,
                    "application/json",
                )
                (duplicate is Either.Left) shouldBe true

                val _ = gcs.put(key, ByteArrayInputStream("v2-new".encodeToByteArray()), 6, "application/json")
                val readV1 = (gcs.getVersion(key, "1") as Either.Right).value.use {
                    it.stream.readAllBytes().decodeToString()
                }
                readV1 shouldBe "v1"
                val readLatest = (gcs.get(key) as Either.Right).value.use {
                    it.stream.readAllBytes().decodeToString()
                }
                readLatest shouldBe "v2-new"

                val presigned = (gcs.presignGet(key, 5.minutes) as Either.Right).value
                presigned.url.toString().contains("X-Goog-Algorithm=GOOG4-RSA-SHA256") shouldBe true
            } finally {
                server.stop(0)
            }
        }
    }

    private fun parseQuery(rawQuery: String): Map<String, String> =
        if (rawQuery.isBlank()) {
            emptyMap()
        } else {
            rawQuery.split('&').mapNotNull { pair ->
                val parts = pair.split('=', limit = 2)
                if (parts.size == 2) {
                    URLDecoder.decode(parts[0], StandardCharsets.UTF_8) to
                        URLDecoder.decode(parts[1], StandardCharsets.UTF_8)
                } else {
                    null
                }
            }.toMap()
        }
}
