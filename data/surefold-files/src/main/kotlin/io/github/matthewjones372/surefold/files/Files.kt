package io.github.matthewjones372.surefold.files

import io.github.matthewjones372.surefold.fail.Either
import java.io.InputStream
import java.time.Instant

/** Why a call on a [Files] did not do what it was asked. */
sealed interface FileError {
    data class NotFound(val key: FileKey) : FileError

    data class AccessDenied(val key: FileKey?, val message: String) : FileError

    data class TooLarge(val key: FileKey, val size: Long, val limit: Long) : FileError

    /** The store did not answer in time, refused for now, or failed on its side: worth trying again later. */
    data class Unavailable(val message: String) : FileError

    data class InvalidKey(val key: String, val why: String) : FileError
}

/** A stored file's metadata. [etag] is the store's, opaque, and changes whenever the content does. */
data class FileMeta(val key: FileKey, val size: Long, val contentType: String, val etag: String, val modified: Instant)

/** A file being read: close it, or the connection under [stream] stays held. */
class FileContent(val meta: FileMeta, val stream: InputStream) : AutoCloseable {
    override fun close() = stream.close()
}

/** One page of a listing, in key order; [next] is passed back as `after` for the page after it, null on the last. */
data class FilePage(val files: List<FileMeta>, val next: String?)

/**
 * A bucket of files: S3 itself, any S3-compatible store, or [InMemoryFiles] in a test. Every call answers its failure
 * as a [FileError]; a store's own exceptions never reach the caller.
 */
interface Files {
    /** The largest file [put] accepts, in bytes. */
    val maxSize: Long

    /** Stores [size] bytes read from [content] at [key], in place of anything there. */
    fun put(key: FileKey, content: InputStream, size: Long, contentType: String): Either<FileError, FileMeta>

    /** Opens [key] for reading. */
    fun get(key: FileKey): Either<FileError, FileContent>

    fun head(key: FileKey): Either<FileError, FileMeta>

    /** Deletes [key]. Deleting a key that is not there succeeds, as S3 does. */
    fun delete(key: FileKey): Either<FileError, Unit>

    /** Up to [limit] files whose keys start with [prefix], after the key [after]. */
    fun list(prefix: String, after: String? = null, limit: Int = 1_000): Either<FileError, FilePage>

    /** Copies [from] to [to] within the bucket, without reading it through this process. */
    fun copy(from: FileKey, to: FileKey): Either<FileError, FileMeta>
}
