package com.cookie.identity.security

import com.cookie.identity.config.IdentityProperties
import com.cookie.identity.domain.UuidV7Generator
import com.nimbusds.jose.JWSAlgorithm
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
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission

@Configuration(proxyBeanMethods = false)
class KeyMaterialConfiguration {
    @Bean
    @Profile("!dev & !test")
    fun productionKeyMaterial(properties: IdentityProperties): KeyMaterial = KeyMaterial(
        signingKey = loadJwk(properties.jwtPrivateKeyPath, ECKey::parse)
            ?: error("COOKIE_IDENTITY_JWT_PRIVATE_KEY_PATH must point to a private EC JWK"),
        retiringSigningKeys = loadRetiringKeys(properties),
        notificationEncryptionKey = loadJwk(properties.notificationPublicKeyPath, RSAKey::parse)
            ?.toPublicJWK()
            ?: error("COOKIE_NOTIFICATION_PUBLIC_KEY_PATH must point to a public RSA JWK"),
    )

    @Bean
    @Profile("dev", "test")
    fun developmentKeyMaterial(
        properties: IdentityProperties,
        uuidV7Generator: UuidV7Generator,
    ): KeyMaterial {
        val signingKey = loadJwk(properties.jwtPrivateKeyPath, ECKey::parse)
            ?: ECKeyGenerator(Curve.P_256)
                .keyUse(KeyUse.SIGNATURE)
                .algorithm(JWSAlgorithm.ES256)
                .keyID(uuidV7Generator.next().toString())
                .generate()
                .also { logger.warn("Using an ephemeral Identity JWT signing key kid={}", it.keyID) }
        val notificationKey = loadJwk(properties.notificationPublicKeyPath, RSAKey::parse)
            ?.toPublicJWK()
            ?: RSAKeyGenerator(2048)
                .keyUse(KeyUse.ENCRYPTION)
                .keyID(uuidV7Generator.next().toString())
                .generate()
                .also { key ->
                    logger.warn("Using an ephemeral Notification encryption key kid={}", key.keyID)
                    writeDevPrivateKey(properties.devNotificationPrivateKeyOutputPath, key)
                }
                .toPublicJWK()
        return KeyMaterial(signingKey, loadRetiringKeys(properties), notificationKey)
    }

    private fun loadRetiringKeys(properties: IdentityProperties): List<ECKey> =
        properties.jwtRetiringPublicKeyPaths
            .mapNotNull { path -> loadJwk(path, ECKey::parse)?.toPublicJWK() }

    private fun <T> loadJwk(rawPath: String, parser: (String) -> T): T? =
        rawPath.takeIf(String::isNotBlank)?.let { path -> parser(Files.readString(Path.of(path))) }

    private fun writeDevPrivateKey(rawPath: String, key: RSAKey) {
        if (rawPath.isBlank()) return
        val output = Path.of(rawPath)
        output.parent?.let(Files::createDirectories)
        Files.writeString(
            output,
            key.toJSONString(),
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
        runCatching {
            Files.setPosixFilePermissions(
                output,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(KeyMaterialConfiguration::class.java)
    }
}
