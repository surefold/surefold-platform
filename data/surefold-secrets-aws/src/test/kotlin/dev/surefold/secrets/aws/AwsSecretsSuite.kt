package dev.surefold.secrets.aws

import dev.surefold.Shared
import dev.surefold.fail.Either
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.secrets.SecretError
import dev.surefold.secrets.SecretValue
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse
import software.amazon.awssdk.services.secretsmanager.model.InvalidRequestException
import software.amazon.awssdk.services.secretsmanager.model.ResourceNotFoundException
import software.amazon.awssdk.services.ssm.SsmClient
import software.amazon.awssdk.services.ssm.model.GetParameterRequest
import software.amazon.awssdk.services.ssm.model.GetParameterResponse
import software.amazon.awssdk.services.ssm.model.GetParametersByPathRequest
import software.amazon.awssdk.services.ssm.model.GetParametersByPathResponse
import software.amazon.awssdk.services.ssm.model.Parameter
import software.amazon.awssdk.services.ssm.model.ParameterNotFoundException
import java.util.function.Consumer

object AwsSecretsSuite : Suite() {
    init {
        test("AwsSecretsManager reads current and pending stages and maps missing secrets to NotFound") {
            val client = FakeSecretsClient(
                mapOf(
                    "db/app:AWSCURRENT" to "current-pass",
                    "db/app:AWSPENDING" to "pending-pass",
                ),
            )
            val secrets = AwsSecretsManager(client)
            secrets.getSecret("db/app") shouldBe Either.Right(SecretValue("db/app", "v1", "current-pass"))
            secrets.getSecret("db/app", "AWSPENDING") shouldBe
                Either.Right(SecretValue("db/app", "AWSPENDING", "pending-pass"))
            secrets.getSecret("db/app", "AWSPREVIOUS") shouldBe Either.Left(SecretError.NotFound("db/app"))
            secrets.getSecret("missing") shouldBe Either.Left(SecretError.NotFound("missing"))
            secrets.close()
            client.closed.peek() shouldBe true
        }

        test("AwsSsmParameters reads single parameters and paginates getParametersByPath with decryption") {
            val client = FakeSsmClient(
                mapOf(
                    "/doorlist/prod/a" to "val-a",
                    "/doorlist/prod/b" to "val-b",
                    "/doorlist/prod/c" to "val-c",
                ),
            )
            val ssm = AwsSsmParameters(client)
            ssm.getParameter("/doorlist/prod/a") shouldBe Either.Right("val-a")
            ssm.getParameter("/doorlist/prod/missing") shouldBe
                Either.Left(SecretError.NotFound("/doorlist/prod/missing"))
            ssm.getParametersByPath("/doorlist/prod/") shouldBe Either.Right(
                mapOf(
                    "/doorlist/prod/a" to "val-a",
                    "/doorlist/prod/b" to "val-b",
                    "/doorlist/prod/c" to "val-c",
                ),
            )
            client.decryptFlags.peek() shouldBe listOf(true, true, true, true)
            ssm.close()
            client.closed.peek() shouldBe true
        }
    }
}

private class FakeSecretsClient(private val entries: Map<String, String>) : SecretsManagerClient {
    val closed = Shared(false)

    override fun serviceName(): String = "secretsmanager"
    override fun close() {
        val _ = closed.update { true }
    }

    override fun getSecretValue(request: Consumer<GetSecretValueRequest.Builder>): GetSecretValueResponse {
        val built = GetSecretValueRequest.builder().also { request.accept(it) }.build()
        val id = built.secretId()
        if (id == "missing") throw ResourceNotFoundException.builder().build()
        val stage = built.versionStage() ?: built.versionId() ?: "AWSCURRENT"
        val value = entries["$id:$stage"] ?: throw InvalidRequestException.builder().build()
        return GetSecretValueResponse.builder().versionId("v1").secretString(value).build()
    }
}

private class FakeSsmClient(private val entries: Map<String, String>) : SsmClient {
    val closed = Shared(false)
    val decryptFlags = Shared(emptyList<Boolean>())

    override fun serviceName(): String = "ssm"
    override fun close() {
        val _ = closed.update { true }
    }

    override fun getParameter(request: Consumer<GetParameterRequest.Builder>): GetParameterResponse {
        val built = GetParameterRequest.builder().also { request.accept(it) }.build()
        val _ = decryptFlags.update { it + (built.withDecryption() == true) }
        val value = entries[built.name()] ?: throw ParameterNotFoundException.builder().build()
        return GetParameterResponse.builder()
            .parameter(Parameter.builder().name(built.name()).value(value).build())
            .build()
    }

    override fun getParametersByPath(
        request: Consumer<GetParametersByPathRequest.Builder>,
    ): GetParametersByPathResponse {
        val built = GetParametersByPathRequest.builder().also { request.accept(it) }.build()
        val _ = decryptFlags.update { it + (built.withDecryption() == true) }
        val all = entries.entries.filter { it.key.startsWith(built.path()) }
        val offset = built.nextToken()?.toIntOrNull() ?: 0
        val slice = all.drop(offset).take(2)
        val next = (offset + 2).takeIf { it < all.size }?.toString()
        return GetParametersByPathResponse.builder()
            .parameters(slice.map { Parameter.builder().name(it.key).value(it.value).build() })
            .nextToken(next)
            .build()
    }
}
