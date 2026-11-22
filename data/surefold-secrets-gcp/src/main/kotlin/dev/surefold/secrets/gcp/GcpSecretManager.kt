package dev.surefold.secrets.gcp

import dev.surefold.fail.Either
import dev.surefold.fail.attempt
import dev.surefold.fail.bind
import dev.surefold.fail.ensure
import dev.surefold.fail.ensureNotNull
import dev.surefold.fail.left
import dev.surefold.fail.map
import dev.surefold.fail.mapLeft
import dev.surefold.fail.right
import dev.surefold.secrets.ParameterStore
import dev.surefold.secrets.SecretError
import dev.surefold.secrets.SecretStore
import dev.surefold.secrets.SecretValue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Base64
import java.util.zip.CRC32C

/**
 * Fetches an OAuth2 access token from the Cloud Run / GCE metadata server (`metadata.google.internal`), or from an
 * explicit supplier in tests.
 */
fun interface GcpTokenSource {
    fun accessToken(): Either<SecretError, String>

    companion object {
        private val DEFAULT_METADATA_URI =
            URI.create("http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/token")

        private const val METADATA_CONNECT_SECONDS = 2L
        private const val METADATA_TIMEOUT_SECONDS = 5L

        fun metadata(
            http: HttpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(METADATA_CONNECT_SECONDS)).build(),
            uri: URI = DEFAULT_METADATA_URI,
        ): GcpTokenSource = GcpTokenSource {
            val request = HttpRequest.newBuilder(uri)
                .header("Metadata-Flavor", "Google")
                .timeout(Duration.ofSeconds(METADATA_TIMEOUT_SECONDS))
                .GET()
                .build()
            Either.catch { http.send(request, HttpResponse.BodyHandlers.ofString()) }
                .mapLeft { SecretError.Unavailable("GCP metadata server could not be reached: ${it.message}") }
                .let { result ->
                    when (result) {
                        is Either.Left -> result

                        is Either.Right -> {
                            val response = result.value
                            if (response.statusCode() != 200) {
                                SecretError.Unavailable("GCP metadata server answered ${response.statusCode()}").left()
                            } else {
                                parseToken(response.body())
                            }
                        }
                    }
                }
        }

        private fun parseToken(body: String): Either<SecretError, String> =
            Either.catch {
                Json.parseToJsonElement(body).jsonObject["access_token"]?.jsonPrimitive?.content
            }.mapLeft { SecretError.Unavailable("GCP metadata token response was invalid JSON") }
                .let { parsed ->
                    when (parsed) {
                        is Either.Left -> parsed

                        is Either.Right -> {
                            val token = parsed.value
                            if (token.isNullOrBlank()) {
                                SecretError.Unavailable("GCP metadata response contained no access_token").left()
                            } else {
                                token.right()
                            }
                        }
                    }
                }
    }
}

/**
 * [SecretStore] and [ParameterStore] on Google Cloud Secret Manager REST v1 (`secretmanager.googleapis.com/v1`) over
 * the JDK's [HttpClient], with CRC32C payload verification and zero gRPC dependencies.
 */
class GcpSecretManager(
    private val projectId: String,
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS)).build(),
    private val endpoint: URI = DEFAULT_ENDPOINT,
    private val tokens: GcpTokenSource = GcpTokenSource.metadata(http),
) : SecretStore, ParameterStore {

    override fun getSecret(id: String, version: String?): Either<SecretError, SecretValue> = attempt {
        ensure(id.isNotBlank()) { SecretError.Invalid(id, "secret id is blank") }
        val token = tokens.accessToken().bind()
        val resolvedVersion = when (version) {
            null, "AWSCURRENT", "latest" -> "latest"
            "AWSPENDING" -> "pending"
            else -> version
        }
        val secretResource = secretResourcePath(id)
        val uri = endpoint.resolve("/v1/$secretResource/versions/$resolvedVersion:access")
        val body = requestJson(uri, token, id).bind()
        val versionName = body["name"]?.jsonPrimitive?.content ?: "$secretResource/versions/$resolvedVersion"
        val actualVersion = versionName.substringAfterLast('/')
        val payload = ensureNotNull(body["payload"]?.jsonObject) {
            SecretError.Invalid(id, "Secret Manager response contained no payload")
        }
        val encoded = ensureNotNull(payload["data"]?.jsonPrimitive?.content) {
            SecretError.Invalid(id, "Secret Manager payload contained no data")
        }
        val bytes = Either.catch { Base64.getDecoder().decode(encoded) }
            .mapLeft { SecretError.Invalid(id, "Secret Manager payload was not valid Base64") }
            .bind()
        val expectedCrc = payload["dataCrc32c"]?.jsonPrimitive?.content?.toLongOrNull()
        if (expectedCrc != null) {
            val crc = CRC32C().apply { update(bytes) }.value
            ensure(crc == expectedCrc) {
                SecretError.Unavailable("Secret Manager CRC32C mismatch for $id")
            }
        }
        SecretValue(id, actualVersion, bytes.decodeToString())
    }

    override fun getParameter(name: String): Either<SecretError, String> {
        val secretId = normaliseParameterName(name)
        return getSecret(secretId).map { it.value }.mapLeft { error ->
            when (error) {
                is SecretError.NotFound -> SecretError.NotFound(name)
                is SecretError.AccessDenied -> SecretError.AccessDenied(name, error.reason)
                is SecretError.Invalid -> SecretError.Invalid(name, error.reason)
                is SecretError.Unavailable -> error
            }
        }
    }

    override fun getParametersByPath(prefix: String): Either<SecretError, Map<String, String>> = attempt {
        val token = tokens.accessToken().bind()
        val normalisedPrefix = normaliseParameterName(prefix).trimEnd('-') + "-"
        val filter = URLEncoder.encode("name:$normalisedPrefix", StandardCharsets.UTF_8)
        val results = linkedMapOf<String, String>()
        var pageToken: String? = null
        do {
            val query = buildString {
                append("filter=").append(filter)
                if (pageToken != null) {
                    append("&pageToken=").append(URLEncoder.encode(pageToken, StandardCharsets.UTF_8))
                }
            }
            val uri = endpoint.resolve("/v1/projects/$projectId/secrets?$query")
            val page = requestJson(uri, token, prefix).bind()
            val secrets = page["secrets"]?.jsonArray.orEmpty()
            for (element in secrets) {
                val obj = element.jsonObject
                val fullName = obj["name"]?.jsonPrimitive?.content ?: continue
                val shortId = fullName.substringAfterLast('/')
                if (shortId.startsWith(normalisedPrefix)) {
                    val suffix = shortId.removePrefix(normalisedPrefix)
                    val paramPath = prefix.trimEnd('/') + "/" + suffix
                    val secretValue = getSecret(shortId).bind()
                    results[paramPath] = secretValue.value
                }
            }
            pageToken = page["nextPageToken"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        } while (pageToken != null)
        results.toMap()
    }

    override fun close() = Unit

    private fun secretResourcePath(id: String): String =
        if (id.startsWith("projects/")) {
            id.substringBefore("/versions/")
        } else {
            "projects/$projectId/secrets/${normaliseParameterName(id)}"
        }

    private fun requestJson(uri: URI, token: String, id: String): Either<SecretError, JsonObject> {
        val request = HttpRequest.newBuilder(uri)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
            .GET()
            .build()
        val response = Either.catch { http.send(request, HttpResponse.BodyHandlers.ofString()) }
            .mapLeft { SecretError.Unavailable("Secret Manager could not be reached: ${it.message}") }
        return when (response) {
            is Either.Left -> response

            is Either.Right -> {
                val httpResponse = response.value
                when (val status = httpResponse.statusCode()) {
                    200 -> Either.catch { Json.parseToJsonElement(httpResponse.body()).jsonObject }
                        .mapLeft { SecretError.Unavailable("Secret Manager returned invalid JSON") }

                    404 -> SecretError.NotFound(id).left()

                    401, 403 -> SecretError.AccessDenied(id, "HTTP $status").left()

                    400 -> SecretError.Invalid(id, "HTTP 400").left()

                    else -> SecretError.Unavailable("Secret Manager answered HTTP $status").left()
                }
            }
        }
    }

    companion object {
        val DEFAULT_ENDPOINT: URI = URI.create("https://secretmanager.googleapis.com")
        private const val CONNECT_TIMEOUT_SECONDS = 5L
        private const val REQUEST_TIMEOUT_SECONDS = 10L

        /** Converts `/doorlist/prod/token-key` or `doorlist/prod/token-key` to `doorlist-prod-token-key`. */
        fun normaliseParameterName(name: String): String =
            name.trim('/').replace('/', '-')
    }
}
