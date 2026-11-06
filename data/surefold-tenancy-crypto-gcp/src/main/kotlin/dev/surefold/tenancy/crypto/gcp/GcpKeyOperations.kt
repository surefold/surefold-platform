package dev.surefold.tenancy.crypto.gcp

import dev.surefold.Shared
import dev.surefold.fail.Either
import dev.surefold.fail.attempt
import dev.surefold.fail.bind
import dev.surefold.fail.ensure
import dev.surefold.fail.ensureNotNull
import dev.surefold.fail.left
import dev.surefold.fail.map
import dev.surefold.fail.mapLeft
import dev.surefold.fail.right
import dev.surefold.tenancy.crypto.DeletionProgress
import dev.surefold.tenancy.crypto.DeletionState
import dev.surefold.tenancy.crypto.EncryptionContext
import dev.surefold.tenancy.crypto.ExpectedKeySet
import dev.surefold.tenancy.crypto.GeneratedKey
import dev.surefold.tenancy.crypto.KeyContext
import dev.surefold.tenancy.crypto.KeyError
import dev.surefold.tenancy.crypto.KeyFailureCode
import dev.surefold.tenancy.crypto.KeyOperation
import dev.surefold.tenancy.crypto.KeyOperationFailure
import dev.surefold.tenancy.crypto.KeyOperations
import dev.surefold.tenancy.crypto.KeyOwner
import dev.surefold.tenancy.crypto.KeyTags
import dev.surefold.tenancy.crypto.Kms
import dev.surefold.tenancy.crypto.RegionalDeletion
import dev.surefold.tenancy.crypto.RootCreation
import dev.surefold.tenancy.crypto.RootExpectation
import dev.surefold.tenancy.crypto.RootKey
import dev.surefold.tenancy.crypto.RootListing
import dev.surefold.tenancy.crypto.RootLocation
import dev.surefold.tenancy.crypto.RootMetadata
import dev.surefold.tenancy.crypto.RootState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.zip.CRC32C
import kotlin.time.Duration
import java.time.Duration as JavaDuration

/** Fetches an OAuth2 access token from the GCE/Cloud Run metadata server or a test supplier. */
fun interface GcpKmsTokenSource {
    fun accessToken(operation: KeyOperation): Either<KeyOperationFailure, String>

    companion object {
        private val DEFAULT_METADATA_URI =
            URI.create("http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/token")

        private const val METADATA_CONNECT_SECONDS = 2L
        private const val METADATA_TIMEOUT_SECONDS = 5L

        fun metadata(
            http: HttpClient = HttpClient.newBuilder()
                .connectTimeout(JavaDuration.ofSeconds(METADATA_CONNECT_SECONDS)).build(),
            uri: URI = DEFAULT_METADATA_URI,
        ): GcpKmsTokenSource = GcpKmsTokenSource { op ->
            val request = HttpRequest.newBuilder(uri)
                .header("Metadata-Flavor", "Google")
                .timeout(JavaDuration.ofSeconds(METADATA_TIMEOUT_SECONDS))
                .GET()
                .build()
            val response = Either.catch { http.send(request, HttpResponse.BodyHandlers.ofString()) }
                .mapLeft { KeyOperationFailure(op, KeyFailureCode.Unavailable) }
            when (response) {
                is Either.Left -> response

                is Either.Right -> {
                    if (response.value.statusCode() != 200) {
                        KeyOperationFailure(op, KeyFailureCode.Unavailable).left()
                    } else {
                        val token = Either.catch {
                            Json.parseToJsonElement(response.value.body())
                                .jsonObject["access_token"]?.jsonPrimitive?.content
                        }.getOrNull()
                        if (token.isNullOrBlank()) {
                            KeyOperationFailure(op, KeyFailureCode.Unavailable).left()
                        } else {
                            token.right()
                        }
                    }
                }
            }
        }
    }
}

/**
 * [KeyOperations] on Google Cloud KMS REST v1 (`cloudkms.googleapis.com/v1`) over the JDK's [HttpClient].
 *
 * Envelope data keys are generated as 32 CSPRNG bytes in-process and wrapped via Cloud KMS `:encrypt` with canonical
 * `additionalAuthenticatedData` derived from [EncryptionContext] and full CRC32C request/response verification.
 */
class GcpKeyOperations(
    private val projectId: String,
    private val keyRing: String,
    private val preferredDataRegion: String? = null,
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(JavaDuration.ofSeconds(CONNECT_TIMEOUT_SECONDS)).build(),
    private val endpoint: URI = DEFAULT_ENDPOINT,
    private val tokens: GcpKmsTokenSource = GcpKmsTokenSource.metadata(http),
) : KeyOperations {

    private val random = SecureRandom()
    private val aliasBindings = Shared(emptyMap<String, String>(), "gcp-kms-aliases")

    override fun generateDataKey(
        root: RootKey,
        context: EncryptionContext,
    ): Either<KeyOperationFailure, GeneratedKey> {
        val plaintext = ByteArray(KEY_BYTES).also(random::nextBytes)
        return when (val wrapped = encryptBytes(root, plaintext, context, KeyOperation.Generate)) {
            is Either.Left -> {
                plaintext.fill(0)
                wrapped
            }

            is Either.Right -> GeneratedKey(plaintext, wrapped.value).right()
        }
    }

    override fun wrap(
        root: RootKey,
        plaintext: ByteArray,
        context: EncryptionContext,
    ): Either<KeyOperationFailure, ByteArray> {
        if (plaintext.isEmpty()) {
            return KeyOperationFailure(KeyOperation.Wrap, KeyFailureCode.InvalidInput).left()
        }
        return encryptBytes(root, plaintext, context, KeyOperation.Wrap)
    }

    override fun unwrap(
        root: RootKey,
        wrapped: ByteArray,
        context: EncryptionContext,
    ): Either<KeyOperationFailure, ByteArray> = attempt {
        val op = KeyOperation.Unwrap
        ensure(wrapped.isNotEmpty()) { KeyOperationFailure(op, KeyFailureCode.InvalidInput) }
        val targetPath = resolveDataPath(root, op).bind()
        val token = tokens.accessToken(op).bind()
        val aad = canonicalAad(context)
        val wrappedCrc = crc32c(wrapped)
        val aadCrc = crc32c(aad)
        val payload = buildJsonObject {
            put("ciphertext", JsonPrimitive(Base64.getEncoder().encodeToString(wrapped)))
            put("ciphertextCrc32c", JsonPrimitive(wrappedCrc.toString()))
            put("additionalAuthenticatedData", JsonPrimitive(Base64.getEncoder().encodeToString(aad)))
            put("additionalAuthenticatedDataCrc32c", JsonPrimitive(aadCrc.toString()))
        }.toString()
        val body = postJson("/v1/$targetPath:decrypt", payload, token, op).bind()
        val encodedPlain = ensureNotNull(body["plaintext"]?.jsonPrimitive?.content) {
            KeyOperationFailure(op, KeyFailureCode.IncompleteEvidence)
        }
        val plaintext = Either.catch { Base64.getDecoder().decode(encodedPlain) }
            .mapLeft { KeyOperationFailure(op, KeyFailureCode.IncompleteEvidence) }
            .bind()
        val expectedCrc = ensureNotNull(body["plaintextCrc32c"]?.jsonPrimitive?.content?.toLongOrNull()) {
            plaintext.fill(0)
            KeyOperationFailure(op, KeyFailureCode.IncompleteEvidence)
        }
        if (crc32c(plaintext) != expectedCrc || plaintext.isEmpty()) {
            plaintext.fill(0)
            fail(KeyOperationFailure(op, KeyFailureCode.IncompleteEvidence))
        }
        plaintext
    }

    override fun describe(root: RootKey): Either<KeyOperationFailure, RootMetadata> = attempt {
        val op = KeyOperation.Describe
        val parsed = parseResourceName(root.id, op).bind()
        val token = tokens.accessToken(op).bind()
        val body = getJson("/v1/${parsed.resourceName}", token, op).bind()
        parseMetadata(body, parsed)
    }

    override fun listRoots(region: String): Either<KeyOperationFailure, RootListing> = attempt {
        val op = KeyOperation.ListRoots
        ensure(region.isNotBlank()) { KeyOperationFailure(op, KeyFailureCode.InvalidInput) }
        val token = tokens.accessToken(op).bind()
        val locations = mutableListOf<RootLocation>()
        var pageToken: String? = null
        do {
            val query = if (pageToken == null) {
                ""
            } else {
                "?pageToken=" + URLEncoder.encode(pageToken, StandardCharsets.UTF_8)
            }
            val path = "/v1/projects/$projectId/locations/$region/keyRings/$keyRing/cryptoKeys$query"
            val body = getJson(path, token, op).bind()
            val keys = body["cryptoKeys"]?.jsonArray.orEmpty()
            for (item in keys) {
                val name = item.jsonObject["name"]?.jsonPrimitive?.content
                    ?: fail(KeyOperationFailure(op, KeyFailureCode.IncompleteEvidence))
                val parsed = parseResourceName(name, op).bind()
                locations += parsed.toLocation()
            }
            pageToken = body["nextPageToken"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        } while (pageToken != null)
        RootListing(locations)
    }

    override fun resolveAlias(region: String, alias: String): Either<KeyOperationFailure, RootMetadata> = attempt {
        val op = KeyOperation.ResolveAlias
        ensure(region.isNotBlank() && alias.startsWith("alias/")) {
            KeyOperationFailure(op, KeyFailureCode.InvalidInput)
        }
        val cryptoKeyId = normaliseAliasToKeyId(alias)
        val boundRoot = aliasBindings.peek()["$region:$alias"]
            ?: "projects/$projectId/locations/$region/keyRings/$keyRing/cryptoKeys/$cryptoKeyId"
        val parsed = parseResourceName(boundRoot, op).bind()
        val token = tokens.accessToken(op).bind()
        val body = getJson("/v1/${parsed.resourceName}", token, op).bind()
        parseMetadata(body, parsed)
    }

    override fun tags(root: RootKey): Either<KeyOperationFailure, KeyTags> = attempt {
        val op = KeyOperation.Tags
        val parsed = parseResourceName(root.id, op).bind()
        val token = tokens.accessToken(op).bind()
        val body = getJson("/v1/${parsed.resourceName}", token, op).bind()
        val labelsObj = body["labels"]?.jsonObject
        val labels = labelsObj?.entries?.associate { (k, v) -> k to v.jsonPrimitive.content }.orEmpty()
        KeyTags(labels)
    }

    override fun createPrimary(request: RootCreation): Either<KeyOperationFailure, RootMetadata> =
        createKeyInRegion(request, KeyOperation.CreatePrimary, primaryLocation = null)

    override fun bindAlias(root: RootKey, alias: String): Either<KeyOperationFailure, Unit> = attempt {
        val op = KeyOperation.BindAlias
        ensure(alias.startsWith("alias/")) { KeyOperationFailure(op, KeyFailureCode.InvalidInput) }
        val parsed = parseResourceName(root.id, op).bind()
        val key = "${parsed.region}:$alias"
        val existing = aliasBindings.peek()[key]
        if (existing != null && existing != parsed.resourceName) {
            fail(KeyOperationFailure(op, KeyFailureCode.Conflict))
        }
        aliasBindings.update { it + (key to parsed.resourceName) }
    }

    override fun enableRotation(root: RootKey): Either<KeyOperationFailure, Unit> = attempt {
        val op = KeyOperation.EnableRotation
        val parsed = parseResourceName(root.id, op).bind()
        val token = tokens.accessToken(op).bind()
        val payload = buildJsonObject {
            put("rotationPeriod", JsonPrimitive("7776000s"))
        }.toString()
        patchJson(
            "/v1/${parsed.resourceName}?updateMask=rotationPeriod",
            payload,
            token,
            op,
        ).bind()
    }

    override fun createReplica(primary: RootKey, request: RootCreation): Either<KeyOperationFailure, RootMetadata> =
        attempt {
            val op = KeyOperation.CreateReplica
            val primaryParsed = parseResourceName(primary.id, op).bind()
            createKeyInRegion(request, op, primaryLocation = primaryParsed.toLocation()).bind()
        }

    override fun inspectReplica(primary: RootKey, replica: RootKey): Either<KeyOperationFailure, RootMetadata> =
        attempt {
            val op = KeyOperation.InspectReplica
            val primaryParsed = parseResourceName(primary.id, op).bind()
            val replicaParsed = parseResourceName(replica.id, op).bind()
            ensure(primaryParsed.cryptoKeyId == replicaParsed.cryptoKeyId) {
                KeyOperationFailure(op, KeyFailureCode.Conflict)
            }
            val token = tokens.accessToken(op).bind()
            val body = getJson("/v1/${replicaParsed.resourceName}", token, op).bind()
            parseMetadata(body, replicaParsed, primaryOverride = primaryParsed.toLocation())
        }

    override fun scheduleDeletion(expected: ExpectedKeySet, waitingDays: Int): DeletionProgress {
        val op = KeyOperation.ScheduleDeletion
        if (waitingDays !in MIN_WAITING_DAYS..MAX_WAITING_DAYS) {
            return DeletionProgress(emptyList(), KeyOperationFailure(op, KeyFailureCode.InvalidInput))
        }
        val allExpected = expected.replicas + expected.primary
        for (item in allExpected) {
            when (val verified = verifyBeforeDeletion(item, op)) {
                is Either.Left -> return DeletionProgress(emptyList(), verified.value)
                is Either.Right -> Unit
            }
        }
        val accepted = mutableListOf<RegionalDeletion>()
        for (item in allExpected) {
            when (val destroyed = destroyPrimaryVersion(item.location, op)) {
                is Either.Left -> return DeletionProgress(accepted, destroyed.value)
                is Either.Right -> accepted += destroyed.value
            }
        }
        return DeletionProgress(accepted, null)
    }

    private fun verifyBeforeDeletion(
        expectation: RootExpectation,
        op: KeyOperation,
    ): Either<KeyOperationFailure, Unit> = attempt {
        val meta = describe(expectation.location.root).mapLeft { KeyOperationFailure(op, it.code) }.bind()
        ensure(meta.location == expectation.location) { KeyOperationFailure(op, KeyFailureCode.Conflict) }
        val observedTags = tags(expectation.location.root).mapLeft { KeyOperationFailure(op, it.code) }.bind()
        val tagsMatch = expectation.requiredTags.all { (k, v) -> observedTags.entries[k] == v }
        ensure(tagsMatch) { KeyOperationFailure(op, KeyFailureCode.Conflict) }
    }

    private fun destroyPrimaryVersion(
        location: RootLocation,
        op: KeyOperation,
    ): Either<KeyOperationFailure, RegionalDeletion> = attempt {
        val parsed = parseResourceName(location.root.id, op).bind()
        val token = tokens.accessToken(op).bind()
        val body = postJson(
            "/v1/${parsed.resourceName}/cryptoKeyVersions/1:destroy",
            "{}",
            token,
            op,
        ).bind()
        val destroyTime = body["destroyTime"]?.jsonPrimitive?.content?.let {
            Either.catch { Instant.parse(it) }.getOrNull()
        }
        RegionalDeletion(location, DeletionState.PendingDeletion, destroyTime)
    }

    private fun createKeyInRegion(
        request: RootCreation,
        op: KeyOperation,
        primaryLocation: RootLocation?,
    ): Either<KeyOperationFailure, RootMetadata> = attempt {
        ensure(request.region.isNotBlank() && request.description.isNotBlank()) {
            KeyOperationFailure(op, KeyFailureCode.InvalidInput)
        }
        val token = tokens.accessToken(op).bind()
        val keyId = request.tags["surefold:key-id"] ?: "mrk-${UUID.randomUUID().toString().replace("-", "")}"
        val labelsObj = buildJsonObject {
            request.tags.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
        }
        val payload = buildJsonObject {
            put("purpose", JsonPrimitive("ENCRYPT_DECRYPT"))
            put("labels", labelsObj)
        }.toString()
        val query = "cryptoKeyId=" + URLEncoder.encode(keyId, StandardCharsets.UTF_8)
        val path = "/v1/projects/$projectId/locations/${request.region}/keyRings/$keyRing/cryptoKeys?$query"
        val body = postJson(path, payload, token, op).bind()
        val name = body["name"]?.jsonPrimitive?.content
            ?: "projects/$projectId/locations/${request.region}/keyRings/$keyRing/cryptoKeys/$keyId"
        val parsed = parseResourceName(name, op).bind()
        parseMetadata(body, parsed, primaryOverride = primaryLocation)
    }

    private fun encryptBytes(
        root: RootKey,
        plaintext: ByteArray,
        context: EncryptionContext,
        op: KeyOperation,
    ): Either<KeyOperationFailure, ByteArray> = attempt {
        val targetPath = resolveDataPath(root, op).bind()
        val token = tokens.accessToken(op).bind()
        val aad = canonicalAad(context)
        val plainCrc = crc32c(plaintext)
        val aadCrc = crc32c(aad)
        val payload = buildJsonObject {
            put("plaintext", JsonPrimitive(Base64.getEncoder().encodeToString(plaintext)))
            put("plaintextCrc32c", JsonPrimitive(plainCrc.toString()))
            put("additionalAuthenticatedData", JsonPrimitive(Base64.getEncoder().encodeToString(aad)))
            put("additionalAuthenticatedDataCrc32c", JsonPrimitive(aadCrc.toString()))
        }.toString()
        val body = postJson("/v1/$targetPath:encrypt", payload, token, op).bind()
        val verifiedPlain = body["verifiedPlaintextCrc32c"]?.jsonPrimitive?.booleanOrNull ?: false
        val verifiedAad = body["verifiedAdditionalAuthenticatedDataCrc32c"]?.jsonPrimitive?.booleanOrNull ?: false
        ensure(verifiedPlain && verifiedAad) {
            KeyOperationFailure(op, KeyFailureCode.IncompleteEvidence)
        }
        val encodedCipher = ensureNotNull(body["ciphertext"]?.jsonPrimitive?.content) {
            KeyOperationFailure(op, KeyFailureCode.IncompleteEvidence)
        }
        val ciphertext = Either.catch { Base64.getDecoder().decode(encodedCipher) }
            .mapLeft { KeyOperationFailure(op, KeyFailureCode.IncompleteEvidence) }
            .bind()
        val expectedCrc = ensureNotNull(body["ciphertextCrc32c"]?.jsonPrimitive?.content?.toLongOrNull()) {
            KeyOperationFailure(op, KeyFailureCode.IncompleteEvidence)
        }
        ensure(crc32c(ciphertext) == expectedCrc && ciphertext.isNotEmpty()) {
            KeyOperationFailure(op, KeyFailureCode.IncompleteEvidence)
        }
        ciphertext
    }

    private fun resolveDataPath(root: RootKey, op: KeyOperation): Either<KeyOperationFailure, String> = attempt {
        val parsed = parseResourceName(root.id, op).bind()
        val region = preferredDataRegion?.takeIf { it.isNotBlank() } ?: parsed.region
        "projects/${parsed.project}/locations/$region/keyRings/${parsed.keyRing}/cryptoKeys/${parsed.cryptoKeyId}"
    }

    private fun parseMetadata(
        body: JsonObject,
        parsed: ParsedKeyName,
        primaryOverride: RootLocation? = null,
    ): RootMetadata {
        val primaryObj = body["primary"]?.jsonObject
        val rawState = primaryObj?.get("state")?.jsonPrimitive?.content
            ?: body["state"]?.jsonPrimitive?.content
            ?: "ENABLED"
        val state = when (rawState) {
            "ENABLED" -> RootState.Enabled
            "DISABLED", "DESTROYED" -> RootState.Disabled
            "DESTROY_SCHEDULED" -> RootState.PendingDeletion
            "PENDING_GENERATION", "CREATING" -> RootState.Creating
            else -> RootState.Unknown
        }
        val location = parsed.toLocation()
        return RootMetadata(
            location = location,
            state = state,
            primary = primaryOverride ?: location,
            replicas = emptyList(),
        )
    }

    private data class ParsedKeyName(
        val project: String,
        val region: String,
        val keyRing: String,
        val cryptoKeyId: String,
    ) {
        val resourceName: String
            get() = "projects/$project/locations/$region/keyRings/$keyRing/cryptoKeys/$cryptoKeyId"

        fun toLocation(): RootLocation = RootLocation(
            root = RootKey(resourceName),
            account = project,
            partition = "gcp",
            region = region,
        )
    }

    private fun parseResourceName(raw: String, op: KeyOperation): Either<KeyOperationFailure, ParsedKeyName> {
        val match = KEY_NAME_REGEX.matchEntire(raw)
            ?: return KeyOperationFailure(op, KeyFailureCode.InvalidInput).left()
        val (proj, loc, ring, keyId) = match.destructured
        return ParsedKeyName(proj, loc, ring, keyId).right()
    }

    private fun getJson(
        path: String,
        token: String,
        op: KeyOperation,
    ): Either<KeyOperationFailure, JsonObject> {
        val request = HttpRequest.newBuilder(endpoint.resolve(path))
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .timeout(JavaDuration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
            .GET()
            .build()
        return sendJson(request, op)
    }

    private fun postJson(
        path: String,
        jsonBody: String,
        token: String,
        op: KeyOperation,
    ): Either<KeyOperationFailure, JsonObject> {
        val request = HttpRequest.newBuilder(endpoint.resolve(path))
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .timeout(JavaDuration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
            .build()
        return sendJson(request, op)
    }

    private fun patchJson(
        path: String,
        jsonBody: String,
        token: String,
        op: KeyOperation,
    ): Either<KeyOperationFailure, JsonObject> {
        val request = HttpRequest.newBuilder(endpoint.resolve(path))
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .timeout(JavaDuration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
            .method("PATCH", HttpRequest.BodyPublishers.ofString(jsonBody))
            .build()
        return sendJson(request, op)
    }

    private fun sendJson(
        request: HttpRequest,
        op: KeyOperation,
    ): Either<KeyOperationFailure, JsonObject> {
        val response = Either.catch { http.send(request, HttpResponse.BodyHandlers.ofString()) }
            .mapLeft {
                val code = if (op in MUTATION_OPS) {
                    KeyFailureCode.UncertainMutation
                } else {
                    KeyFailureCode.Unavailable
                }
                KeyOperationFailure(op, code)
            }
        return when (response) {
            is Either.Left -> response

            is Either.Right -> {
                val httpResponse = response.value
                when (val status = httpResponse.statusCode()) {
                    200 -> Either.catch { Json.parseToJsonElement(httpResponse.body()).jsonObject }
                        .mapLeft { KeyOperationFailure(op, KeyFailureCode.IncompleteEvidence) }

                    400 -> {
                        val code = if (op == KeyOperation.Unwrap) {
                            KeyFailureCode.InvalidCiphertext
                        } else {
                            KeyFailureCode.InvalidInput
                        }
                        KeyOperationFailure(op, code).left()
                    }

                    401, 403 -> KeyOperationFailure(op, KeyFailureCode.Refused).left()

                    404 -> {
                        val code = when (op) {
                            KeyOperation.Describe, KeyOperation.ResolveAlias, KeyOperation.Tags ->
                                KeyFailureCode.NotObserved

                            else -> KeyFailureCode.Refused
                        }
                        KeyOperationFailure(op, code).left()
                    }

                    409 -> KeyOperationFailure(op, KeyFailureCode.Conflict).left()

                    412 -> KeyOperationFailure(op, KeyFailureCode.Refused).left()

                    else -> {
                        val code = if (status >= 500 && op in MUTATION_OPS) {
                            KeyFailureCode.UncertainMutation
                        } else {
                            KeyFailureCode.Unavailable
                        }
                        KeyOperationFailure(op, code).left()
                    }
                }
            }
        }
    }

    companion object {
        val DEFAULT_ENDPOINT: URI = URI.create("https://cloudkms.googleapis.com")
        private const val CONNECT_TIMEOUT_SECONDS = 5L
        private const val REQUEST_TIMEOUT_SECONDS = 10L
        private const val KEY_BYTES = 32
        private const val MIN_WAITING_DAYS = 7
        private const val MAX_WAITING_DAYS = 30
        private val MUTATION_OPS = setOf(
            KeyOperation.CreatePrimary,
            KeyOperation.BindAlias,
            KeyOperation.EnableRotation,
            KeyOperation.CreateReplica,
            KeyOperation.ScheduleDeletion,
        )
        private val KEY_NAME_REGEX = Regex(
            "^projects/([^/]+)/locations/([^/]+)/keyRings/([^/]+)/cryptoKeys/([^/]+)(?:/cryptoKeyVersions/[^/]+)?$",
        )

        internal fun canonicalAad(context: EncryptionContext): ByteArray =
            context.entries.entries
                .sortedBy { it.key }
                .joinToString("&") { (k, v) -> "$k=$v" }
                .toByteArray(StandardCharsets.UTF_8)

        internal fun crc32c(bytes: ByteArray): Long =
            CRC32C().apply { update(bytes) }.value

        /** Maps an alias like `alias/doorlist/prod/platform-data` to a valid Cloud KMS CryptoKey ID. */
        fun normaliseAliasToKeyId(alias: String): String =
            alias.removePrefix("alias/").trim('/').replace('/', '-')
    }
}

/**
 * [Kms] adapter backed by [GcpKeyOperations] for [dev.surefold.tenancy.crypto.TenantKeys].
 */
class GcpKms(
    private val operations: GcpKeyOperations,
    private val region: String,
    private val aliasPrefix: String,
) : Kms {

    init {
        require(aliasPrefix.startsWith("alias/") && aliasPrefix.endsWith("/")) {
            "an alias prefix is alias/…/, such as alias/doorlist/prod/tenant/, not $aliasPrefix"
        }
    }

    override fun createRoot(owner: KeyOwner): Either<KeyError, RootKey> {
        val alias = aliasPrefix + ownerSuffix(owner)
        val existing = operations.resolveAlias(region, alias)
        if (existing is Either.Right) {
            return existing.value.location.root.right()
        }
        val keyId = GcpKeyOperations.normaliseAliasToKeyId(alias)
        val creation = RootCreation(
            region = region,
            policy = "gcp-iam",
            description = "surefold-tenancy-crypto root key for ${ownerSuffix(owner)}",
            tags = mapOf("surefold:key-id" to keyId, "surefold_owner" to ownerSuffix(owner)),
        )
        return operations.createPrimary(creation)
            .map { meta ->
                operations.bindAlias(meta.location.root, alias)
                meta.location.root
            }
            .mapLeft(::toKeyError)
    }

    override fun generateDataKey(root: RootKey, context: KeyContext): Either<KeyError, GeneratedKey> =
        operations.generateDataKey(root, EncryptionContext(context.entries())).mapLeft(::toKeyError)

    override fun decrypt(root: RootKey, wrapped: ByteArray, context: KeyContext): Either<KeyError, ByteArray> =
        operations.unwrap(root, wrapped, EncryptionContext(context.entries())).mapLeft(::toKeyError)

    override fun scheduleDeletion(root: RootKey, waiting: Duration): Either<KeyError, Unit> {
        val days = waiting.inWholeDays.toInt().coerceIn(MIN_WAITING_DAYS, MAX_WAITING_DAYS)
        return when (val described = operations.describe(root)) {
            is Either.Left -> toKeyError(described.value).left()

            is Either.Right -> {
                val expected = ExpectedKeySet(RootExpectation(described.value.location, emptyMap()), emptyList())
                val progress = operations.scheduleDeletion(expected, days)
                val failure = progress.failure
                if (failure == null) Unit.right() else toKeyError(failure).left()
            }
        }
    }

    private fun ownerSuffix(owner: KeyOwner): String = when (owner) {
        is KeyOwner.Tenant -> owner.id.value
        KeyOwner.Platform -> "platform"
    }

    private fun toKeyError(failure: KeyOperationFailure): KeyError {
        val reason = "gcp-kms ${failure.operation.name}: ${failure.code.name}"
        return when (failure.code) {
            KeyFailureCode.InvalidCiphertext, KeyFailureCode.InvalidInput -> KeyError.Invalid(reason)

            KeyFailureCode.Refused, KeyFailureCode.NotObserved, KeyFailureCode.Conflict -> KeyError.Denied(reason)

            KeyFailureCode.Unavailable, KeyFailureCode.IncompleteEvidence, KeyFailureCode.UncertainMutation ->
                KeyError.Unavailable(reason)
        }
    }

    private companion object {
        const val MIN_WAITING_DAYS = 7
        const val MAX_WAITING_DAYS = 30
    }
}
