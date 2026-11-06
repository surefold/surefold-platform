package dev.surefold.secrets

import dev.surefold.Shared
import dev.surefold.fail.Either
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldThrow
import java.io.PrintWriter
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.SQLException
import java.util.logging.Logger
import javax.sql.DataSource

object SecretsSuite : Suite() {
    init {
        test("InMemorySecrets stores versioned secrets and redacts values in toString") {
            val secrets = InMemorySecrets()
            secrets.put("db/app", "super-secret-value", version = "v1")
            val current = secrets.getSecret("db/app")
            (current is Either.Right) shouldBe true
            val value = (current as Either.Right).value
            value.value shouldBe "super-secret-value"
            value.toString().contains("super-secret-value") shouldBe false
            secrets.getSecret("db/app", "v1") shouldBe Either.Right(SecretValue("db/app", "v1", "super-secret-value"))
            secrets.getSecret("missing") shouldBe Either.Left(SecretError.NotFound("missing"))
        }

        test("InMemoryParameters reads single parameters and path prefixes") {
            val params = InMemoryParameters(
                mapOf(
                    "/doorlist/prod/token-key" to "tok-1",
                    "/doorlist/prod/totp-key" to "totp-1",
                    "/doorlist/test/token-key" to "tok-test",
                ),
            )
            params.getParameter("/doorlist/prod/token-key") shouldBe Either.Right("tok-1")
            params.getParameter("/doorlist/prod/missing") shouldBe
                Either.Left(SecretError.NotFound("/doorlist/prod/missing"))
            params.getParametersByPath("/doorlist/prod/") shouldBe Either.Right(
                mapOf(
                    "/doorlist/prod/token-key" to "tok-1",
                    "/doorlist/prod/totp-key" to "totp-1",
                ),
            )
        }

        test("RotatingDataSource refreshes credentials on 28P01 and swaps the pool once") {
            val secrets = InMemorySecrets()
            secrets.put("db/app", "new-password", version = "2")
            val activePassword = Shared("new-password")
            val builtPools = Shared(emptyList<String>())
            val closedPools = Shared(emptyList<String>())

            val rotating = RotatingDataSource(
                secrets = secrets,
                secretId = "db/app",
                initialPassword = "old-password",
            ) { password ->
                val _ = builtPools.update { it + password }
                FakeDataSource(
                    password = password,
                    expectedPassword = activePassword,
                    onClose = { val _ = closedPools.update { it + password } },
                )
            }

            val conn = rotating.connection
            conn.isClosed shouldBe false
            builtPools.peek() shouldBe listOf("old-password", "new-password")
            closedPools.peek() shouldBe listOf("old-password")
            rotating.close()
        }

        test("RotatingDataSource propagates non-authentication SQL exceptions without refreshing") {
            val secrets = InMemorySecrets()
            secrets.put("db/app", "new-password")
            val builtPools = Shared(0)
            val rotating = RotatingDataSource(
                secrets = secrets,
                secretId = "db/app",
                initialPassword = "old-password",
            ) { _ ->
                val _ = builtPools.update { it + 1 }
                object : DataSource by FakeDataSource("old-password", Shared("old-password"), {}) {
                    override fun getConnection(): Connection = throw SQLException("syntax error", "42601")
                }
            }
            val _ = shouldThrow<SQLException> { rotating.connection }
            builtPools.peek() shouldBe 1
        }
    }
}

private class FakeDataSource(
    private val password: String,
    private val expectedPassword: Shared<String>,
    private val onClose: () -> Unit,
) : DataSource, AutoCloseable {
    override fun getConnection(): Connection {
        if (password != expectedPassword.peek()) {
            throw SQLException("password authentication failed", "28P01")
        }
        return Proxy.newProxyInstance(
            Connection::class.java.classLoader,
            arrayOf(Connection::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "isClosed" -> false
                "close" -> Unit
                "toString" -> "FakeConnection"
                else -> null
            }
        } as Connection
    }

    override fun getConnection(username: String?, password: String?): Connection = getConnection()
    override fun close() = onClose()
    override fun getLogWriter(): PrintWriter? = null
    override fun setLogWriter(out: PrintWriter?) = Unit
    override fun setLoginTimeout(seconds: Int) = Unit
    override fun getLoginTimeout(): Int = 0
    override fun getParentLogger(): Logger = Logger.getGlobal()
    override fun <T : Any?> unwrap(iface: Class<T>): T = throw SQLException("not a wrapper")
    override fun isWrapperFor(iface: Class<*>): Boolean = false
}
