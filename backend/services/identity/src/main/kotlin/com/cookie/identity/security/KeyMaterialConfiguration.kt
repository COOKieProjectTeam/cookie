package com.cookie.identity.security

import com.cookie.identity.application.ports.IdGenerator
import com.cookie.identity.config.IdentityProperties
import com.nimbusds.jose.EncryptionMethod
import com.nimbusds.jose.JWEAlgorithm
import com.nimbusds.jose.JWEHeader
import com.nimbusds.jose.JWEObject
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.Payload
import com.nimbusds.jose.crypto.RSADecrypter
import com.nimbusds.jose.crypto.RSAEncrypter
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission

@Configuration(proxyBeanMethods = false)
class KeyMaterialConfiguration {
    @Bean
    @Profile("!dev & !test")
    fun productionKeyMaterial(properties: IdentityProperties): KeyMaterial = KeyMaterial(
        signingKey = loadRequiredJwk(
            properties.jwtPrivateKeyPath,
            "COOKIE_IDENTITY_JWT_PRIVATE_KEY_PATH",
            ECKey::parse,
        ),
        retiringSigningKeys = loadRetiringKeys(properties),
        notificationEncryptionKey = loadRequiredJwk(
            properties.notificationPublicKeyPath,
            "COOKIE_NOTIFICATION_PUBLIC_KEY_PATH",
            RSAKey::parse,
        ),
    )

    @Bean
    @Profile("dev", "test")
    fun developmentKeyMaterial(
        properties: IdentityProperties,
        idGenerator: IdGenerator,
    ): KeyMaterial {
        val signingKey = loadOptionalJwk(properties.jwtPrivateKeyPath, "Identity signing JWK", ECKey::parse)
            ?: ECKeyGenerator(Curve.P_256)
                .keyUse(KeyUse.SIGNATURE)
                .algorithm(JWSAlgorithm.ES256)
                .keyID(idGenerator.next().toString())
                .generate()
                .also { logger.warn("Using an ephemeral Identity JWT signing key kid={}", it.keyID) }
        val notificationKey = loadOptionalJwk(
            properties.notificationPublicKeyPath,
            "Notification encryption JWK",
            RSAKey::parse,
        ) ?: loadOrCreateDevelopmentNotificationKey(properties.devNotificationPrivateKeyOutputPath, idGenerator)
            .toPublicJWK()
        return KeyMaterial(signingKey, loadRetiringKeys(properties), notificationKey)
    }

    private fun loadRetiringKeys(properties: IdentityProperties): List<ECKey> =
        properties.jwtRetiringPublicKeyPaths.mapIndexed { index, path ->
            loadRequiredJwk(path, "cookie.identity.jwt-retiring-public-key-paths[$index]", ECKey::parse)
        }

    private fun <T> loadRequiredJwk(rawPath: String, propertyName: String, parser: (String) -> T): T {
        if (rawPath.isBlank()) error("$propertyName must point to a JWK file")
        return loadJwk(rawPath, propertyName, parser)
    }

    private fun <T> loadOptionalJwk(rawPath: String, label: String, parser: (String) -> T): T? =
        rawPath.takeIf(String::isNotBlank)?.let { loadJwk(it, label, parser) }

    private fun <T> loadJwk(rawPath: String, label: String, parser: (String) -> T): T {
        val path = Path.of(rawPath)
        if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
            error("$label must point to a readable JWK file")
        }
        return try {
            parser(Files.readString(path))
        } catch (exception: Exception) {
            throw IllegalStateException("$label does not contain a valid JWK", exception)
        }
    }

    private fun loadOrCreateDevelopmentNotificationKey(rawPath: String, idGenerator: IdGenerator): RSAKey {
        if (rawPath.isBlank()) return generateDevelopmentNotificationKey(idGenerator)
            .also { logger.warn("Using an ephemeral Notification encryption key kid={}", it.keyID) }

        val output = Path.of(rawPath).toAbsolutePath()
        output.parent?.let(Files::createDirectories)
        val lockPath = output.resolveSibling(".${output.fileName}.lock")
        return FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use {
                val key = if (Files.exists(output)) {
                    loadJwk(output.toString(), "Persisted development Notification private JWK", RSAKey::parse)
                } else {
                    generateDevelopmentNotificationKey(idGenerator).also { generated ->
                        writeDevPrivateKey(output, generated)
                        logger.warn("Created a persistent development Notification encryption key kid={}", generated.keyID)
                    }
                }
                validateDevelopmentNotificationPrivateKey(key)
            }
        }
    }

    private fun generateDevelopmentNotificationKey(idGenerator: IdGenerator): RSAKey =
        RSAKeyGenerator(MINIMUM_RSA_BITS)
            .keyUse(KeyUse.ENCRYPTION)
            .algorithm(JWEAlgorithm.RSA_OAEP_256)
            .keyID(idGenerator.next().toString())
            .generate()

    private fun validateDevelopmentNotificationPrivateKey(key: RSAKey): RSAKey {
        require(key.isPrivate) { "Persisted development Notification JWK must contain private key material" }
        require(key.toRSAPublicKey().modulus.bitLength() >= MINIMUM_RSA_BITS) {
            "Persisted development Notification JWK must contain an RSA key of at least $MINIMUM_RSA_BITS bits"
        }
        require(key.keyUse == KeyUse.ENCRYPTION) { "Persisted development Notification JWK must declare use=enc" }
        require(key.algorithm == JWEAlgorithm.RSA_OAEP_256) {
            "Persisted development Notification JWK must declare alg=RSA-OAEP-256"
        }
        require(!key.keyID.isNullOrBlank()) { "Persisted development Notification JWK must have kid" }
        try {
            val probe = JWEObject(
                JWEHeader.Builder(JWEAlgorithm.RSA_OAEP_256, EncryptionMethod.A256GCM)
                    .keyID(key.keyID)
                    .build(),
                Payload(SELF_TEST_PAYLOAD),
            )
            probe.encrypt(RSAEncrypter(key.toPublicJWK()))
            val compact = probe.serialize()
            JWEObject.parse(compact).apply { decrypt(RSADecrypter(key)) }.also { decrypted ->
                require(decrypted.payload.toString() == SELF_TEST_PAYLOAD) {
                    "Persisted development Notification JWK self-test produced a different payload"
                }
            }
        } catch (exception: Exception) {
            throw IllegalArgumentException(
                "Persisted development Notification JWK failed its startup self-test",
                exception,
            )
        }
        return key
    }

    private fun writeDevPrivateKey(output: Path, key: RSAKey) {
        val temporary = Files.createTempFile(output.parent, ".${output.fileName}.", ".tmp")
        try {
            restrictPrivateKeyPermissions(temporary)
            Files.writeString(
                temporary,
                key.toJSONString(),
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            )
            try {
                Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING)
            }
            restrictPrivateKeyPermissions(output)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun restrictPrivateKeyPermissions(path: Path) {
        runCatching {
            Files.setPosixFilePermissions(
                path,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        }.onFailure { exception ->
            logger.warn("Could not restrict permissions on the development Notification private key", exception)
        }
    }

    companion object {
        private const val MINIMUM_RSA_BITS = 2048
        private const val SELF_TEST_PAYLOAD = "cookie-notification-key-self-test"
        private val logger = LoggerFactory.getLogger(KeyMaterialConfiguration::class.java)
    }
}
