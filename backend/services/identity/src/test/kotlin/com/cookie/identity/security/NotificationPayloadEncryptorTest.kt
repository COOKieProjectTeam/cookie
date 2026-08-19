package com.cookie.identity.security

import com.cookie.identity.config.IdentityProperties
import com.cookie.identity.domain.UuidV7Generator
import com.nimbusds.jose.EncryptionMethod
import com.nimbusds.jose.JWEAlgorithm
import com.nimbusds.jose.JWEObject
import com.nimbusds.jose.crypto.RSADecrypter
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class NotificationPayloadEncryptorTest {
    @TempDir
    lateinit var tempDirectory: Path

    @Test
    fun `encrypts recipient and verification token as compact jwe`() {
        val rsaPrivate = RSAKeyGenerator(2048).keyID("notification-test").generate()
        val publicJwk = tempDirectory.resolve("notification-public.jwk")
        Files.writeString(publicJwk, rsaPrivate.toPublicJWK().toJSONString())
        val clock = Clock.fixed(Instant.parse("2026-08-19T12:00:00Z"), ZoneOffset.UTC)
        val properties = IdentityProperties(notificationPublicKeyPath = publicJwk.toString())
        val keyMaterial = KeyMaterialConfiguration().developmentKeyMaterial(
            properties,
            UuidV7Generator(clock, SecureRandom()),
        )
        val mapper = JsonMapper.builder().findAndAddModules().build()
        val encryptor = NotificationPayloadEncryptor(keyMaterial, mapper)
        val rawToken = "v1.0198c4a5-68b5-7def-8123-456789abcdef.secret"

        val compact = encryptor.encrypt(
            VerificationDelivery(
                recipientEmail = "user@example.ru",
                locale = "ru-RU",
                token = rawToken,
                expiresAt = Instant.parse("2026-08-19T12:30:00Z"),
            ),
        )

        assertThat(compact).doesNotContain("user@example.ru", rawToken)
        val jwe = JWEObject.parse(compact)
        assertThat(jwe.header.algorithm).isEqualTo(JWEAlgorithm.RSA_OAEP_256)
        assertThat(jwe.header.encryptionMethod).isEqualTo(EncryptionMethod.A256GCM)
        jwe.decrypt(RSADecrypter(rsaPrivate))
        assertThat(jwe.payload.toString()).contains("user@example.ru", rawToken, "ru-RU")
    }
}
