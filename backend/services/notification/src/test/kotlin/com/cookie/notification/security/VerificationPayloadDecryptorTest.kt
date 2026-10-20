package com.cookie.notification.security

import com.cookie.notification.delivery.NotificationTemplates
import com.nimbusds.jose.EncryptionMethod
import com.nimbusds.jose.JWEAlgorithm
import com.nimbusds.jose.JWEHeader
import com.nimbusds.jose.JWEObject
import com.nimbusds.jose.Payload
import com.nimbusds.jose.crypto.RSAEncrypter
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

class VerificationPayloadDecryptorTest {
    @TempDir lateinit var directory: Path
    private val now = Instant.parse("2026-09-17T12:00:00Z")
    private val mapper = JsonMapper.builder().build()
    private val attempt = UUID.fromString("0198c4a5-68b5-7def-8123-456789abcdef")
    private val token = "v1e.$attempt.0198c4a5-68b5-7def-9234-56789abcdef0.${"A".repeat(43)}"

    @Test fun `decrypts Identity delivery with both active and retiring keys without exposing secrets`() {
        val active = key("current")
        val retiring = key("retiring")
        val decryptor = decryptor(active, retiring)
        listOf(active, retiring).forEach {
            val result = decryptor.decrypt(encrypt(it), attempt, now.plusSeconds(300))
            assertThat(result.recipient).isEqualTo("person@example.test")
            assertThat(result.token).isEqualTo(token)
            assertThat(result.locale).isEqualTo("en")
            assertThat(result.toString()).doesNotContain(token, "person@example.test")
        }
    }

    @ParameterizedTest(name = "locale JSON: {0}")
    @ValueSource(strings = ["<omitted>", "null", "\"de-DE\"", "\"\"", "42", "\"ru-RU\""])
    fun `Identity email with absent null or unsupported locale uses Russian fallback`(localeJson: String) {
        val key = key("current")
        val compact = encrypt(key, localeJson = localeJson.takeUnless { it == "<omitted>" })
        val email = decryptor(key).decrypt(compact, attempt, now.plusSeconds(300))
        assertThat(email.recipient).isEqualTo("person@example.test")
        assertThat(email.token).isEqualTo(token)
        assertThat(email.locale).isEqualTo("ru")
        val template = NotificationTemplates(URI.create("https://cookie.invalid/verify-email")).verification(email)
        assertThat(template.subject).isEqualTo("Подтвердите email в COOKie")
    }

    @Test fun `rejects expiry aggregate or encryption profile substitution`() {
        val key = key("current")
        val decryptor = decryptor(key)
        assertThatThrownBy { decryptor.decrypt(encrypt(key), attempt, now.plusSeconds(600)) }
            .isInstanceOf(InvalidVerificationPayloadException::class.java)
        assertThatThrownBy { decryptor.decrypt(encrypt(key), UUID.randomUUID(), now.plusSeconds(300)) }
            .isInstanceOf(InvalidVerificationPayloadException::class.java)
        assertThatThrownBy { decryptor.decrypt(encrypt(key, EncryptionMethod.A128GCM), attempt, now.plusSeconds(300)) }
            .isInstanceOf(InvalidVerificationPayloadException::class.java)
    }

    @Test fun `missing decryption kid is unavailable rather than plaintext fallback`() {
        val active = key("one")
        val unknown = key("two")
        assertThatThrownBy { decryptor(active).decrypt(encrypt(unknown), attempt, now.plusSeconds(300)) }
            .isInstanceOf(DecryptionKeyUnavailableException::class.java)
    }

    @Test fun `lost mounted key fails readiness and decryption after bounded cache expires`() {
        val active = key("current")
        val path = Files.writeString(directory.resolve("current.jwk"), active.toJSONString())
        var current = now
        val clock = object : Clock() {
            override fun instant(): Instant = current
            override fun getZone(): ZoneId = ZoneOffset.UTC
            override fun withZone(zone: ZoneId): Clock = this
        }
        val decryptor = VerificationPayloadDecryptor(listOf(path), mapper, clock, true)
        assertThat(decryptor.ready()).isTrue()
        Files.delete(path)
        current = now.plusSeconds(6)
        assertThat(decryptor.ready()).isFalse()
        assertThatThrownBy { decryptor.decrypt(encrypt(active), attempt, now.plusSeconds(300)) }
            .isInstanceOf(DecryptionKeyUnavailableException::class.java)
        Files.writeString(path, active.toJSONString())
        assertThat(decryptor.ready()).isTrue()
    }

    private fun key(kid: String) = RSAKeyGenerator(2048).keyID(kid).keyUse(KeyUse.ENCRYPTION)
        .algorithm(JWEAlgorithm.RSA_OAEP_256).generate()
    private fun decryptor(vararg keys: RSAKey): VerificationPayloadDecryptor = VerificationPayloadDecryptor(
        keys.map { Files.writeString(directory.resolve("${it.keyID}.jwk"), it.toJSONString()) }, mapper,
        Clock.fixed(now, ZoneOffset.UTC), true)
    private fun encrypt(key: RSAKey, encryption: EncryptionMethod = EncryptionMethod.A256GCM,
                        localeJson: String? = "\"en-GB\""): String {
        val body = mapper.createObjectNode().put("registrationAttemptId", attempt.toString())
            .put("recipientEmail", "person@example.test").put("token", token)
            .put("expiresAt", now.plusSeconds(300).toString())
        if (localeJson != null) body.set("locale", mapper.readTree(localeJson))
        return JWEObject(JWEHeader.Builder(JWEAlgorithm.RSA_OAEP_256, encryption).contentType("application/json")
            .keyID(key.keyID).build(), Payload(mapper.writeValueAsString(body))).apply { encrypt(RSAEncrypter(key)) }.serialize()
    }
}
