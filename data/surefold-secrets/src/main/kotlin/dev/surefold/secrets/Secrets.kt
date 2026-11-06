package dev.surefold.secrets

import dev.surefold.Shared
import dev.surefold.fail.Either
import dev.surefold.fail.left
import dev.surefold.fail.right

/** Why a secret or parameter call did not answer a value; never includes any part of the secret value itself. */
sealed interface SecretError {
    data class NotFound(val id: String) : SecretError

    data class AccessDenied(val id: String, val reason: String) : SecretError

    data class Unavailable(val reason: String) : SecretError

    data class Invalid(val id: String, val reason: String) : SecretError
}

/** A secret's versioned material; [toString] redacts [value] so a log or assertion failure never prints it. */
class SecretValue(val id: String, val version: String, val value: String) {
    override fun toString(): String = "SecretValue(id=$id, version=$version, value=REDACTED)"

    override fun equals(other: Any?): Boolean =
        other is SecretValue && id == other.id && version == other.version && value == other.value

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + version.hashCode()
        result = 31 * result + value.hashCode()
        return result
    }
}

/** Reads versioned secrets from a secret manager (AWS Secrets Manager, GCP Secret Manager, or [InMemorySecrets]). */
interface SecretStore : AutoCloseable {
    /** Reads [version] (or the current/latest version when null) of secret [id]. */
    fun getSecret(id: String, version: String? = null): Either<SecretError, SecretValue>

    override fun close() = Unit
}

/** Reads configuration parameters by name or path prefix (AWS SSM Parameter Store, GCP Secret Manager, or fake). */
interface ParameterStore : AutoCloseable {
    /** Reads the decrypted value of parameter [name]. */
    fun getParameter(name: String): Either<SecretError, String>

    /** Reads all parameters under [prefix], keyed by their full parameter name. */
    fun getParametersByPath(prefix: String): Either<SecretError, Map<String, String>>

    override fun close() = Unit
}

/** An in-memory [SecretStore] for tests and local development, backed by [Shared]. */
class InMemorySecrets(initial: Map<String, Map<String, String>> = emptyMap()) : SecretStore {
    private val store = Shared(initial, "in-memory-secrets")

    /** Sets [version] of [id] to [value], and also updates `"latest"` / `"AWSCURRENT"` when [makeCurrent] is true. */
    fun put(id: String, value: String, version: String = "1", makeCurrent: Boolean = true) {
        store.update { current ->
            val existing = current[id].orEmpty()
            val updated = buildMap {
                putAll(existing)
                put(version, value)
                if (makeCurrent) {
                    put(LATEST, value)
                    put(AWS_CURRENT, value)
                }
            }
            current + (id to updated)
        }
    }

    override fun getSecret(id: String, version: String?): Either<SecretError, SecretValue> {
        val versions = store.peek()[id] ?: return SecretError.NotFound(id).left()
        val target = version ?: LATEST
        val found = versions[target] ?: return SecretError.NotFound(id).left()
        return SecretValue(id, target, found).right()
    }

    companion object {
        const val LATEST = "latest"
        const val AWS_CURRENT = "AWSCURRENT"
        const val AWS_PENDING = "AWSPENDING"
    }
}

/** An in-memory [ParameterStore] for tests and local development, backed by [Shared]. */
class InMemoryParameters(initial: Map<String, String> = emptyMap()) : ParameterStore {
    private val parameters = Shared(initial.toSortedMap(), "in-memory-parameters")

    fun put(name: String, value: String) {
        parameters.update { current -> (current + (name to value)).toSortedMap() }
    }

    override fun getParameter(name: String): Either<SecretError, String> =
        parameters.peek()[name]?.right() ?: SecretError.NotFound(name).left()

    override fun getParametersByPath(prefix: String): Either<SecretError, Map<String, String>> =
        parameters.peek().filterKeys { it.startsWith(prefix) }.right()
}
