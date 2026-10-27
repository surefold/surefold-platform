package io.github.matthewjones372.surefold.files.test

import io.github.matthewjones372.surefold.fail.Either
import io.github.matthewjones372.surefold.files.FileError
import io.github.matthewjones372.surefold.files.Files
import io.github.matthewjones372.surefold.files.FilesConfig
import io.github.matthewjones372.surefold.files.FilesContract
import io.github.matthewjones372.surefold.files.S3Files
import io.github.matthewjones372.surefold.junit.shouldBe
import io.github.matthewjones372.surefold.junit.shouldBeLessThan
import io.github.matthewjones372.surefold.policy
import java.io.ByteArrayInputStream
import java.net.ServerSocket
import java.net.URI
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** S3Files on MinIO keeps the contract the fake keeps, and an unreachable store answers within its deadline. */
object MinioFilesSuite : FilesContract() {
    override fun files(): Files = Minio.files(maxSize = 1_000_000)

    init {
        test("a store nobody answers is unavailable, within the policy's deadline") {
            val nobody = ServerSocket(0).use { it.localPort }
            val files = S3Files.of(
                FilesConfig("b", "us-east-1", "k", "s", endpoint = URI("http://127.0.0.1:$nobody"), pathStyle = true),
                policy("files") { deadline(2.seconds) },
            )
            val started = TimeSource.Monotonic.markNow()
            val answer = files.put(key("x"), ByteArrayInputStream(byteArrayOf(1)), 1, "text/plain")
            started.elapsedNow() shouldBeLessThan 5.seconds
            ((answer as Either.Left).value is FileError.Unavailable) shouldBe true
        }

        test("a wrong secret is access denied") {
            val config = Minio.config()
            val files = S3Files.of(config.copy(secretKey = "wrong"))
            val answer = files.head(key("x"))
            ((answer as Either.Left).value is FileError.AccessDenied) shouldBe true
        }
    }
}
