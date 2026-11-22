package dev.surefold.secrets.aws

import dev.surefold.fail.Either
import dev.surefold.fail.left
import dev.surefold.fail.right
import dev.surefold.secrets.ParameterStore
import dev.surefold.secrets.SecretError
import dev.surefold.secrets.SecretStore
import dev.surefold.secrets.SecretValue
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient
import software.amazon.awssdk.services.secretsmanager.model.DecryptionFailureException
import software.amazon.awssdk.services.secretsmanager.model.InvalidParameterException
import software.amazon.awssdk.services.secretsmanager.model.InvalidRequestException
import software.amazon.awssdk.services.secretsmanager.model.ResourceNotFoundException
import software.amazon.awssdk.services.secretsmanager.model.SecretsManagerException
import software.amazon.awssdk.services.ssm.SsmClient
import software.amazon.awssdk.services.ssm.model.ParameterNotFoundException
import software.amazon.awssdk.services.ssm.model.SsmException

/** [SecretStore] on AWS Secrets Manager, reading string secrets by ID/ARN and optional version stage or ID. */
class AwsSecretsManager(private val client: SecretsManagerClient) : SecretStore {

    override fun getSecret(id: String, version: String?): Either<SecretError, SecretValue> = try {
        val response = client.getSecretValue { request ->
            request.secretId(id)
            applyVersion(request, version)
        }
        val secretString = response.secretString()
        if (secretString == null) {
            SecretError.Invalid(id, "binary secret is not supported").left()
        } else {
            SecretValue(id, version ?: response.versionId() ?: "AWSCURRENT", secretString).right()
        }
    } catch (refused: SecretsManagerException) {
        refused.toSecretError(id, version).left()
    } catch (unreachable: SdkClientException) {
        SecretError.Unavailable(unreachable.message ?: "Secrets Manager could not be reached").left()
    }

    private fun applyVersion(
        request: software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest.Builder,
        version: String?,
    ) {
        if (version == null) return
        if (version in STAGE_NAMES) {
            request.versionStage(version)
        } else {
            request.versionId(version)
        }
    }

    private fun SecretsManagerException.toSecretError(id: String, version: String?): SecretError {
        val code = awsErrorDetails()?.errorCode() ?: javaClass.simpleName
        return when {
            this is ResourceNotFoundException -> SecretError.NotFound(id)
            this is InvalidRequestException && version != null -> SecretError.NotFound(id)
            this is InvalidRequestException || this is InvalidParameterException -> SecretError.Invalid(id, code)
            this is DecryptionFailureException -> SecretError.AccessDenied(id, code)
            statusCode() == FORBIDDEN || code == "AccessDeniedException" -> SecretError.AccessDenied(id, code)
            else -> SecretError.Unavailable("Secrets Manager answered $code")
        }
    }

    override fun close() = client.close()

    companion object {
        private const val FORBIDDEN = 403
        private val STAGE_NAMES = setOf("AWSCURRENT", "AWSPENDING", "AWSPREVIOUS")

        /** A client in [region] on the JDK's own HTTP client. */
        fun inRegion(region: String): AwsSecretsManager = AwsSecretsManager(
            SecretsManagerClient.builder()
                .region(Region.of(region))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build(),
        )
    }
}

/** [ParameterStore] on AWS Systems Manager Parameter Store, always decrypting `SecureString` parameters. */
class AwsSsmParameters(private val client: SsmClient) : ParameterStore {

    override fun getParameter(name: String): Either<SecretError, String> = try {
        val value = client.getParameter { it.name(name).withDecryption(true) }.parameter()?.value()
        if (value == null) {
            SecretError.NotFound(name).left()
        } else {
            value.right()
        }
    } catch (_: ParameterNotFoundException) {
        SecretError.NotFound(name).left()
    } catch (refused: SsmException) {
        refused.toSecretError(name).left()
    } catch (unreachable: SdkClientException) {
        SecretError.Unavailable(unreachable.message ?: "SSM could not be reached").left()
    }

    override fun getParametersByPath(prefix: String): Either<SecretError, Map<String, String>> = try {
        val collected = linkedMapOf<String, String>()
        var nextToken: String? = null
        do {
            val currentToken = nextToken
            val page = client.getParametersByPath { request ->
                request.path(prefix).recursive(true).withDecryption(true)
                if (currentToken != null) request.nextToken(currentToken)
            }
            page.parameters().forEach { param ->
                val name = param.name()
                val value = param.value()
                if (name != null && value != null) {
                    collected[name] = value
                }
            }
            nextToken = page.nextToken()?.takeIf { it.isNotBlank() }
        } while (nextToken != null)
        collected.toMap().right()
    } catch (refused: SsmException) {
        refused.toSecretError(prefix).left()
    } catch (unreachable: SdkClientException) {
        SecretError.Unavailable(unreachable.message ?: "SSM could not be reached").left()
    }

    override fun close() = client.close()

    private fun SsmException.toSecretError(id: String): SecretError {
        val code = awsErrorDetails()?.errorCode() ?: javaClass.simpleName
        return when {
            statusCode() == FORBIDDEN || code == "AccessDeniedException" -> SecretError.AccessDenied(id, code)
            statusCode() == BAD_REQUEST -> SecretError.Invalid(id, code)
            else -> SecretError.Unavailable("SSM answered $code")
        }
    }

    companion object {
        private const val FORBIDDEN = 403
        private const val BAD_REQUEST = 400

        /** A client in [region] on the JDK's own HTTP client. */
        fun inRegion(region: String): AwsSsmParameters = AwsSsmParameters(
            SsmClient.builder()
                .region(Region.of(region))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build(),
        )
    }
}
