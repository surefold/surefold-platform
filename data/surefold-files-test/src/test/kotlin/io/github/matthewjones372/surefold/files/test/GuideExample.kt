package io.github.matthewjones372.surefold.files.test

// The example docs/files.md shows, from the imports down: FilesGuideSuite holds the page to this file.
import io.github.matthewjones372.surefold.fail.Either
import io.github.matthewjones372.surefold.fail.attempt
import io.github.matthewjones372.surefold.fail.bind
import io.github.matthewjones372.surefold.files.FileError
import io.github.matthewjones372.surefold.files.Files
import io.github.matthewjones372.surefold.files.Presigned
import io.github.matthewjones372.surefold.files.TenantPrefix
import java.io.InputStream
import kotlin.time.Duration.Companion.minutes

class Tickets(private val files: Files) {

    /** Stores a ticket under its tenant's prefix, so no tenant's listing ever shows another's. */
    fun store(tenant: String, bookingId: String, pdf: InputStream, size: Long): Either<FileError, String> = attempt {
        val key = TenantPrefix(tenant).key("tickets/$bookingId.pdf").bind()
        files.put(key, pdf, size, "application/pdf").bind().etag
    }

    /** A link the customer's browser can open for ten minutes, without credentials. */
    fun link(tenant: String, bookingId: String): Either<FileError, Presigned> = attempt {
        val key = TenantPrefix(tenant).key("tickets/$bookingId.pdf").bind()
        files.head(key).bind()
        files.presignGet(key, expiresIn = 10.minutes).bind()
    }
}
