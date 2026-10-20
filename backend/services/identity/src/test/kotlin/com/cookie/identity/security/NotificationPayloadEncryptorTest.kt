package com.cookie.identity.security

import com.cookie.identity.domain.LocaleTag
import com.cookie.identity.config.IdentityProperties
import com.nimbusds.jose.EncryptionMethod
import com.nimbusds.jose.JWEAlgorithm
import com.nimbusds.jose.JWEObject
import com.nimbusds.jose.crypto.RSADecrypter
import com.nimbusds.jose.jwk.KeyUse
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
import java.util.UUID

class NotificationPayloadEncryptorTest {
    @TempDir
    lateinit var tempDirectory: Path

    @Test
    fun `encrypts recipient and verification token as compact jwe`() {
        val rsaPrivate = RSAKeyGenerator(2048)
            .keyUse(KeyUse.ENCRYPTION)
            .algorithm(JWEAlgorithm.RSA_OAEP_256)
            .keyID("notification-test")
            .generate()
        val publicJwk = tempDirectory.resolve("notification-public.jwk")
        Files.writeString(publicJwk, rsaPrivate.toPublicJWK().toJSONString())
        val clock = Clock.fixed(Instant.parse("2026-08-19T12:00:00Z"), ZoneOffset.UTC)
        val properties = IdentityProperties(notificationPublicKeyPath = publicJwk.toString())
        val keyMaterial = KeyMaterialConfiguration().developmentKeyMaterial(
            properties,
            UuidV7IdGenerator(clock, SecureRandom()),
        )
        val mapper = JsonMapper.builder().findAndAddModules().build()
        val encryptor = NotificationPayloadEncryptor(keyMaterial, mapper)
        val registrationAttemptId = UUID.fromString("0198c4a5-68b5-7def-8123-456789abcdef")
        val tokenId = UUID.fromString("0198c4a5-68b5-7def-9234-56789abcdef0")
        val rawToken = "v1e.$registrationAttemptId.$tokenId.${"A".repeat(43)}"
        val delivery = VerificationDelivery(
            registrationAttemptId = registrationAttemptId,
            recipientEmail = "user@example.ru",
            locale = LocaleTag.parse("ru-RU"),
            token = rawToken,
            expiresAt = Instant.parse("2026-08-19T12:30:00Z"),
        )

        val compact = encryptor.encrypt(delivery)

        assertThat(delivery.toString()).doesNotContain("user@example.ru", rawToken)
        assertThat(compact).doesNotContain("user@example.ru", rawToken)
        val jwe = JWEObject.parse(compact)
        assertThat(jwe.header.algorithm).isEqualTo(JWEAlgorithm.RSA_OAEP_256)
        assertThat(jwe.header.encryptionMethod).isEqualTo(EncryptionMethod.A256GCM)
        jwe.decrypt(RSADecrypter(rsaPrivate))
        val payload = mapper.readTree(jwe.payload.toString())
        assertThat(payload.path("registrationAttemptId").stringValue()).isEqualTo(registrationAttemptId.toString())
        assertThat(payload.path("recipientEmail").stringValue()).isEqualTo("user@example.ru")
        assertThat(payload.path("token").stringValue()).isEqualTo(rawToken)
        assertThat(payload.path("locale").stringValue()).isEqualTo("ru-RU")
    }
}
