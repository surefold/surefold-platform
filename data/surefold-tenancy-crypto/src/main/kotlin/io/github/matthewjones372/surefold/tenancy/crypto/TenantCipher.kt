package io.github.matthewjones372.surefold.tenancy.crypto

import io.github.matthewjones372.surefold.counter
import io.github.matthewjones372.surefold.fail.Either
import io.github.matthewjones372.surefold.fail.left
import io.github.matthewjones372.surefold.fail.right
import io.github.matthewjones372.surefold.increment
import io.github.matthewjones372.surefold.logWarn
import io.github.matthewjones372.surefold.tenancy.currentTenant
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Where a sealed value lives: a ciphertext opens only in the place it was sealed for. */
data class Place(val table: String, val column: String, val row: String)

/** Why a value was not sealed or opened. */
sealed interface SealError {

    /** The owner's key could not be had: see [error]. */
    data class Keys(val error: KeyError) : SealError

    /** Not a value sealed for this owner and place: moved, altered or truncated. Never an empty value. */
    data object Tampered : SealError
}

/**
 * Seals values under an owner's active `enc` key with AES-256-GCM, a fresh random nonce each time, and opens them
 * under the version their envelope names. A ciphertext is bound to its owner and [Place] as associated data, so one
 * copied to another row, column, table or tenant fails as [SealError.Tampered]. With [pad], a value is padded to a
 * multiple of 16 bytes, so a stored length says a bucket rather than how long a name is.
 *
 * The envelope is one `bytea`: format `0x01`, the key version (4 bytes), the nonce (12), then the ciphertext and its
 * 16-byte tag.
 */
class TenantCipher(private val keys: KeyRing, private val pad: Boolean = true) {

    private val random = SecureRandom()

    /** [plaintext] sealed for [place], as the bound tenant. */
    fun seal(plaintext: ByteArray, place: Place): Either<SealError, ByteArray> =
        bound()?.let { seal(it, plaintext, place) } ?: SealError.Keys(KeyError.NoTenant).left()

    /** [plaintext] sealed for [owner] and [place], under its active `enc` key. */
    fun seal(owner: KeyOwner, plaintext: ByteArray, place: Place): Either<SealError, ByteArray> =
        when (val key = keys.current(owner, KeyPurpose.Enc)) {
            is Either.Left -> SealError.Keys(key.value).left()

            is Either.Right -> key.value.using { material ->
                val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
                val header = header(key.value.version)
                val associated = header + associated(owner, place)
                (header + nonce + gcm(Cipher.ENCRYPT_MODE, material, nonce, associated, body(plaintext))).right()
            }
        }

    /** What [sealed] holds, if it was sealed for the bound tenant and [place]. */
    fun open(sealed: ByteArray, place: Place): Either<SealError, ByteArray> =
        bound()?.let { open(it, sealed, place) } ?: SealError.Keys(KeyError.NoTenant).left()

    /** What [sealed] holds, if it was sealed for [owner] and [place]; anything else is [SealError.Tampered]. */
    fun open(owner: KeyOwner, sealed: ByteArray, place: Place): Either<SealError, ByteArray> {
        if (sealed.size < HEADER_BYTES + NONCE_BYTES + TAG_BYTES || sealed[0] != FORMAT) return tampered(place)
        val version = ByteBuffer.wrap(sealed, 1, VERSION_BYTES).int
        return when (val key = keys.version(owner, KeyPurpose.Enc, version)) {
            is Either.Left -> SealError.Keys(key.value).left()

            is Either.Right -> key.value.using { material ->
                val header = sealed.copyOfRange(0, HEADER_BYTES)
                val nonce = sealed.copyOfRange(HEADER_BYTES, HEADER_BYTES + NONCE_BYTES)
                val ciphertext = sealed.copyOfRange(HEADER_BYTES + NONCE_BYTES, sealed.size)
                try {
                    unpadded(gcm(Cipher.DECRYPT_MODE, material, nonce, header + associated(owner, place), ciphertext))
                        ?.right() ?: tampered(place)
                } catch (_: AEADBadTagException) {
                    tampered(place)
                }
            }
        }
    }

    private fun bound(): KeyOwner? = currentTenant()?.let(KeyOwner::Tenant)

    /** The length, the value, and with [pad] zeros to the next multiple of 16 bytes. */
    private fun body(plaintext: ByteArray): ByteArray {
        val length = LENGTH_BYTES + plaintext.size
        val padded = if (pad) (length + BLOCK - 1) / BLOCK * BLOCK else length
        return ByteBuffer.allocate(padded).putInt(plaintext.size).put(plaintext).array()
    }

    private fun unpadded(body: ByteArray): ByteArray? {
        if (body.size < LENGTH_BYTES) return null
        val length = ByteBuffer.wrap(body).int
        if (length < 0 || length > body.size - LENGTH_BYTES) return null
        return body.copyOfRange(LENGTH_BYTES, LENGTH_BYTES + length)
    }

    private fun tampered(place: Place): Either<SealError, Nothing> {
        logWarn("surefold-tenancy-crypto: a value in ${place.table}.${place.column} did not open in its place")
        counter("tenancy.crypto.tampered", "table" to place.table, "column" to place.column).increment()
        return SealError.Tampered.left()
    }

    /** [block] with the key's bytes, which are zeroed after: the cache handed this copy to this call alone. */
    private fun <A> DataKey.using(block: (ByteArray) -> A): A = try {
        block(material)
    } finally {
        material.fill(0)
    }

    internal companion object {
        const val FORMAT: Byte = 0x01
        const val VERSION_BYTES = 4
        const val HEADER_BYTES = 1 + VERSION_BYTES
        const val NONCE_BYTES = 12
        const val TAG_BYTES = 16
        const val TAG_BITS = TAG_BYTES * 8
        const val LENGTH_BYTES = 4
        const val BLOCK = 16

        fun header(version: Int): ByteArray = ByteBuffer.allocate(HEADER_BYTES).put(FORMAT).putInt(version).array()

        /**
         * `surefold/v1`, then the owner and each part of [place] as a 4-byte length and its UTF-8 bytes. Lengths, not
         * a separator, so no table, column or row name can make two places read as one.
         */
        fun associated(owner: KeyOwner, place: Place): ByteArray {
            val parts = listOf(
                when (owner) {
                    is KeyOwner.Tenant -> "tenant:${owner.id.value}"
                    KeyOwner.Platform -> "platform"
                },
                place.table,
                place.column,
                place.row,
            )
            return ByteArrayOutputStream().apply {
                write("surefold/v1".toByteArray(Charsets.UTF_8))
                parts.map { it.toByteArray(Charsets.UTF_8) }.forEach { part ->
                    write(ByteBuffer.allocate(LENGTH_BYTES).putInt(part.size).array())
                    write(part)
                }
            }.toByteArray()
        }

        /** AES-256-GCM, a 128-bit tag; the ciphertext and tag together, as the JDK answers them. */
        fun gcm(mode: Int, key: ByteArray, nonce: ByteArray, associated: ByteArray, input: ByteArray): ByteArray =
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
                updateAAD(associated)
                doFinal(input)
            }
    }
}
