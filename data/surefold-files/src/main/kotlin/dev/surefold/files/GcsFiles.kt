package dev.surefold.files

import dev.surefold.Policy
import dev.surefold.Rejected
import dev.surefold.Shared
import dev.surefold.clock
import dev.surefold.counter
import dev.surefold.fail.Either
import dev.surefold.fail.attempt
import dev.surefold.fail.left
import dev.surefold.fail.right
import dev.surefold.timed
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.HexFormat
import java.util.concurrent.TimeoutException
import kotlin.time.Duration
import kotlin.time.toJavaDuration
import java.time.Duration as JavaDuration

/**
 * Configuration for [GcsFiles] against Google Cloud Storage's JSON/XML API or a local test server.
 * [serviceAccountEmail] and [signBytes] sign V4 `X-Goog-Algorithm=GOOG4-RSA-SHA256` presigned URLs.
 */
data class GcsFilesConfig(
    val bucket: String,
    val endpoint: URI = URI.create("https://storage.googleapis.com"),
    val maxSize: Long = FIVE_GIGABYTES,
    val serviceAccountEmail: String = "doorlist-api@doorlist.iam.gserviceaccount.com",
    val signBytes: (ByteArray) -> ByteArray = { ByteArray(DEFAULT_SIGNATURE_BYTES) },
    val tokenSupplier: () -> Either<FileError, String> = { "anonymous".right() },
) {
    override fun toString(): String =
        "GcsFilesConfig(bucket=$bucket, endpoint=$endpoint, maxSize=$maxSize, sa=$serviceAccountEmail)"
}

private const val FIVE_GIGABYTES = 5L * 1024 * 1024 * 1024
private const val DEFAULT_SIGNATURE_BYTES = 32
private const val CONNECT_TIMEOUT_SECONDS = 5L
private const val REQUEST_TIMEOUT_SECONDS = 10L
private const val HTTP_OK_MIN = 200
private const val HTTP_OK_MAX = 299
private const val ETAG_HEX_LENGTH = 16

/**
 * [Files] on Google Cloud Storage over the JDK's [HttpClient], supporting conditional object creation
 * (`ifGenerationMatch=0`), exact generation reads (`?generation=...`), and V4 presigned URLs with zero Google SDK or
 * gRPC dependencies.
 */
class GcsFiles(
    private val config: GcsFilesConfig,
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(JavaDuration.ofSeconds(CONNECT_TIMEOUT_SECONDS)).build(),
    private val policy: Policy = S3Files.defaultPolicy,
) : Files {

    override val maxSize: Long get() = config.maxSize

    override fun put(
        key: FileKey,
        content: InputStream,
        size: Long,
        contentType: String,
    ): Either<FileError, FileMeta> = uploadObject(key, content, size, contentType, onlyIfAbsent = false)

    /**
     * Stores [size] bytes at [key] only if no object currently exists there (`ifGenerationMatch=0`), answering
     * [FileError.AccessDenied] on HTTP 412 Precondition Failed.
     */
    fun putIfAbsent(
        key: FileKey,
        content: InputStream,
        size: Long,
        contentType: String,
    ): Either<FileError, FileMeta> = uploadObject(key, content, size, contentType, onlyIfAbsent = true)

    private fun uploadObject(
        key: FileKey,
        content: InputStream,
        size: Long,
        contentType: String,
        onlyIfAbsent: Boolean,
    ): Either<FileError, FileMeta> {
        if (size > maxSize) return FileError.TooLarge(key, size, maxSize).left()
        val bytes = content.readNBytes(size.toInt())
        require(bytes.size.toLong() == size && content.read() == -1) {
            "put of $key said $size bytes, and its stream did not hold exactly that."
        }
        return runCall("put", key) { token ->
            val encodedName = urlEncode(key.value)
            val precondition = if (onlyIfAbsent) "&ifGenerationMatch=0" else ""
            val uri = config.endpoint.resolve(
                "/upload/storage/v1/b/${urlEncode(config.bucket)}/o?uploadType=media&name=$encodedName$precondition",
            )
            val request = HttpRequest.newBuilder(uri)
                .header("Authorization", "Bearer $token")
                .header("Content-Type", contentType)
                .timeout(JavaDuration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
                .build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in HTTP_OK_MIN..HTTP_OK_MAX) {
                throw GcsHttpException(response.statusCode(), response.body())
            }
            counter(BYTES, "direction" to "in").increment(size.toDouble())
            parseObjectMeta(key, response.body(), size, contentType)
        }
    }

    override fun get(key: FileKey): Either<FileError, FileContent> = getObject(key, generation = null)

    /** Reads exact object [generation] of [key], or [FileError.NotFound] when that generation is absent. */
    fun getVersion(key: FileKey, generation: String): Either<FileError, FileContent> =
        getObject(key, generation = generation)

    private fun getObject(key: FileKey, generation: String?): Either<FileError, FileContent> =
        runCall("get", key) { token ->
            val meta = headObject(key, generation, token)
            val genQuery = if (generation == null) "" else "&generation=${urlEncode(generation)}"
            val uri = config.endpoint.resolve(
                "/storage/v1/b/${urlEncode(config.bucket)}/o/${urlEncode(key.value)}?alt=media$genQuery",
            )
            val request = HttpRequest.newBuilder(uri)
                .header("Authorization", "Bearer $token")
                .timeout(JavaDuration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                .GET()
                .build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofByteArray())
            if (response.statusCode() !in HTTP_OK_MIN..HTTP_OK_MAX) {
                throw GcsHttpException(response.statusCode(), response.body().decodeToString())
            }
            val bytes = response.body()
            counter(BYTES, "direction" to "out").increment(bytes.size.toDouble())
            FileContent(meta.copy(size = bytes.size.toLong()), ByteArrayInputStream(bytes))
        }

    override fun head(key: FileKey): Either<FileError, FileMeta> =
        runCall("head", key) { token -> headObject(key, generation = null, token = token) }

    private fun headObject(key: FileKey, generation: String?, token: String): FileMeta {
        val genQuery = if (generation == null) "" else "?generation=${urlEncode(generation)}"
        val uri = config.endpoint.resolve(
            "/storage/v1/b/${urlEncode(config.bucket)}/o/${urlEncode(key.value)}$genQuery",
        )
        val request = HttpRequest.newBuilder(uri)
            .header("Authorization", "Bearer $token")
            .timeout(JavaDuration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
            .GET()
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in HTTP_OK_MIN..HTTP_OK_MAX) {
            throw GcsHttpException(response.statusCode(), response.body())
        }
        return parseObjectMeta(key, response.body(), fallbackSize = 0L, fallbackType = null)
    }

    override fun delete(key: FileKey): Either<FileError, Unit> = runCall("delete", key) { token ->
        val uri = config.endpoint.resolve("/storage/v1/b/${urlEncode(config.bucket)}/o/${urlEncode(key.value)}")
        val request = HttpRequest.newBuilder(uri)
            .header("Authorization", "Bearer $token")
            .timeout(JavaDuration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
            .DELETE()
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 404 && response.statusCode() !in HTTP_OK_MIN..HTTP_OK_MAX) {
            throw GcsHttpException(response.statusCode(), response.body())
        }
    }

    override fun list(prefix: String, after: String?, limit: Int): Either<FileError, FilePage> {
        require(limit in 1..MOST_LISTED) { "A listing takes 1 to $MOST_LISTED files, not $limit." }
        return runCall("list", null) { token ->
            val query = buildString {
                append("prefix=").append(urlEncode(prefix))
                append("&maxResults=").append(limit)
                if (after != null) {
                    append("&pageToken=").append(urlEncode(after))
                }
            }
            val uri = config.endpoint.resolve("/storage/v1/b/${urlEncode(config.bucket)}/o?$query")
            val request = HttpRequest.newBuilder(uri)
                .header("Authorization", "Bearer $token")
                .timeout(JavaDuration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                .GET()
                .build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in HTTP_OK_MIN..HTTP_OK_MAX) {
                throw GcsHttpException(response.statusCode(), response.body())
            }
            parseListResponse(response.body())
        }
    }

    override fun copy(from: FileKey, to: FileKey): Either<FileError, FileMeta> = runCall("copy", from) { token ->
        val bkt = urlEncode(config.bucket)
        val uri = config.endpoint.resolve(
            "/storage/v1/b/$bkt/o/${urlEncode(from.value)}/rewriteTo/b/$bkt/o/${urlEncode(to.value)}",
        )
        val request = HttpRequest.newBuilder(uri)
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .timeout(JavaDuration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
            .POST(HttpRequest.BodyPublishers.ofString("{}"))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in HTTP_OK_MIN..HTTP_OK_MAX) {
            throw GcsHttpException(response.statusCode(), response.body())
        }
        headObject(to, generation = null, token = token)
    }

    override fun presignPut(
        key: FileKey,
        expiresIn: Duration,
        contentType: String,
        size: Long,
    ): Either<FileError, Presigned> {
        require(expiresIn > Duration.ZERO && expiresIn <= LONGEST_PRESIGN) { "A presigned URL lasts up to 7 days." }
        if (size > maxSize) return FileError.TooLarge(key, size, maxSize).left()
        val headers = mapOf("content-type" to contentType, "content-length" to "$size")
        return buildV4Presigned("PUT", key, expiresIn, headers).right()
    }

    override fun presignGet(key: FileKey, expiresIn: Duration): Either<FileError, Presigned> {
        require(expiresIn > Duration.ZERO && expiresIn <= LONGEST_PRESIGN) { "A presigned URL lasts up to 7 days." }
        return buildV4Presigned("GET", key, expiresIn, emptyMap()).right()
    }

    override fun multipart(key: FileKey, contentType: String): Either<FileError, MultipartUpload> =
        GcsMultipartUpload(key, contentType).right()

    private inner class GcsMultipartUpload(
        override val key: FileKey,
        private val contentType: String,
    ) : MultipartUpload {
        private val parts = Shared(sortedMapOf<Int, ByteArray>(), "gcs-multipart-parts")

        override fun part(number: Int, content: InputStream, size: Long): Either<FileError, Unit> {
            require(number in 1..MOST_PARTS) { "Parts are numbered 1 to $MOST_PARTS, not $number." }
            val bytes = content.readNBytes(size.toInt())
            parts.update { current -> (current + (number to bytes)).toSortedMap() }
            return Unit.right()
        }

        override fun complete(): Either<FileError, FileMeta> {
            val out = ByteArrayOutputStream()
            parts.peek().values.forEach(out::writeBytes)
            val all = out.toByteArray()
            return put(key, ByteArrayInputStream(all), all.size.toLong(), contentType)
        }

        override fun abort(): Either<FileError, Unit> {
            parts.update { sortedMapOf() }
            return Unit.right()
        }
    }

    private fun buildV4Presigned(
        method: String,
        key: FileKey,
        expiresIn: Duration,
        extraHeaders: Map<String, String>,
    ): Presigned {
        val now = clock.get().now()
        val expiresAt = now + expiresIn.toJavaDuration()
        val dateStamp = DATE_STAMP.format(now)
        val isoTimestamp = ISO_STAMP.format(now)
        val credential = "${config.serviceAccountEmail}/$dateStamp/auto/storage/goog4_request"
        val host = config.endpoint.authority ?: "storage.googleapis.com"
        val allHeaders = (extraHeaders + ("host" to host)).toSortedMap()
        val signedHeaderNames = allHeaders.keys.joinToString(";")
        val query = buildString {
            append("X-Goog-Algorithm=GOOG4-RSA-SHA256")
            append("&X-Goog-Credential=").append(urlEncode(credential))
            append("&X-Goog-Date=").append(isoTimestamp)
            append("&X-Goog-Expires=").append(expiresIn.inWholeSeconds)
            append("&X-Goog-SignedHeaders=").append(urlEncode(signedHeaderNames))
        }
        val canonicalPath = "/${config.bucket}/${key.value}"
        val canonicalHeaders = allHeaders.entries.joinToString("\n") { (k, v) -> "$k:${v.trim()}" } + "\n"
        val canonicalRequest = listOf(
            method,
            canonicalPath,
            query,
            canonicalHeaders,
            signedHeaderNames,
            "UNSIGNED-PAYLOAD",
        ).joinToString("\n")
        val canonicalHash = sha256Hex(canonicalRequest.toByteArray(StandardCharsets.UTF_8))
        val stringToSign = listOf(
            "GOOG4-RSA-SHA256",
            isoTimestamp,
            "$dateStamp/auto/storage/goog4_request",
            canonicalHash,
        ).joinToString("\n")
        val signatureHex = HexFormat.of().formatHex(
            config.signBytes(stringToSign.toByteArray(StandardCharsets.UTF_8)),
        )
        val signedUri = config.endpoint.resolve("$canonicalPath?$query&X-Goog-Signature=$signatureHex")
        return Presigned(signedUri, method, extraHeaders, expiresAt)
    }

    private fun <A> runCall(operation: String, key: FileKey?, block: (String) -> A): Either<FileError, A> = attempt {
        val token = when (val t = config.tokenSupplier()) {
            is Either.Left -> fail(t.value)
            is Either.Right -> t.value
        }
        timed(DURATION, "operation" to operation) {
            try {
                policy { block(token) }
            } catch (httpError: GcsHttpException) {
                fail(httpError.toFileError(key))
            } catch (rejected: Rejected) {
                fail(FileError.Unavailable("$operation was refused by its policy: ${rejected.message}"))
            } catch (late: TimeoutException) {
                fail(FileError.Unavailable("$operation passed its deadline: ${late.message}"))
            } catch (io: IOException) {
                fail(FileError.Unavailable("$operation could not reach GCS: ${io.message}"))
            }
        }
    }

    private class GcsHttpException(val status: Int, val body: String) : RuntimeException("HTTP $status") {
        fun toFileError(key: FileKey?): FileError = when (status) {
            404 -> key?.let(FileError::NotFound) ?: FileError.Unavailable("GCS answered 404")
            401, 403, 412 -> FileError.AccessDenied(key, "GCS answered HTTP $status")
            else -> FileError.Unavailable("GCS answered HTTP $status")
        }
    }

    private fun parseObjectMeta(
        key: FileKey,
        json: String,
        fallbackSize: Long,
        fallbackType: String?,
    ): FileMeta {
        val size = extractJsonField(json, "size")?.toLongOrNull() ?: fallbackSize
        val contentType = extractJsonField(json, "contentType") ?: fallbackType
        val etag = extractJsonField(json, "etag")
            ?: extractJsonField(json, "generation")
            ?: sha256Hex(json.toByteArray(StandardCharsets.UTF_8)).take(ETAG_HEX_LENGTH)
        val updated = extractJsonField(json, "updated")?.let {
            Either.catch { Instant.parse(it) }.getOrNull()
        } ?: clock.get().now()
        return FileMeta(key, size, contentType, etag, updated)
    }

    private fun parseListResponse(json: String): FilePage {
        val nextToken = extractJsonField(json, "nextPageToken")?.takeIf { it.isNotBlank() }
        val itemsBlock = json.substringAfter("\"items\"", "").substringBefore("]", "")
        if (itemsBlock.isBlank()) return FilePage(emptyList(), null)
        val objectBlocks = itemsBlock.split('{').drop(1)
        val files = objectBlocks.mapNotNull { block ->
            val rawObject = "{$block"
            val name = extractJsonField(rawObject, "name") ?: return@mapNotNull null
            val key = FileKey.of(name).orThrow()
            val size = extractJsonField(rawObject, "size")?.toLongOrNull() ?: 0L
            val etag = extractJsonField(rawObject, "etag") ?: "etag"
            val updated = extractJsonField(rawObject, "updated")?.let {
                Either.catch { Instant.parse(it) }.getOrNull()
            } ?: clock.get().now()
            FileMeta(key, size, null, etag, updated)
        }
        return FilePage(files, nextToken)
    }

    companion object {
        private const val DURATION = "surefold.files.duration.seconds"
        private const val BYTES = "surefold.files.bytes"
        private const val MOST_LISTED = 1_000
        private const val MOST_PARTS = 10_000
        private val DATE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC)
        private val ISO_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

        private fun urlEncode(value: String): String =
            URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

        private fun sha256Hex(bytes: ByteArray): String =
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

        private fun extractJsonField(json: String, field: String): String? {
            val regex = Regex(""""$field"\s*:\s*"([^"]*)"""")
            return regex.find(json)?.groupValues?.get(1)
        }
    }
}
