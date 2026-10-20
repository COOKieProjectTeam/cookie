package com.cookie.notification.security

import com.nimbusds.jose.EncryptionMethod
import com.nimbusds.jose.JWEAlgorithm
import com.nimbusds.jose.JWEObject
import com.nimbusds.jose.JWEHeader
import com.nimbusds.jose.Payload
import com.nimbusds.jose.crypto.RSADecrypter
import com.nimbusds.jose.crypto.RSAEncrypter
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.RSAKey
import tools.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.util.UUID

class InvalidVerificationPayloadException : RuntimeException("Invalid verification payload")
class DecryptionKeyUnavailableException : RuntimeException("Decryption key unavailable")

/** Intentionally not a data class: generated toString would disclose recipient/token. */
class VerificationEmail(val recipient: String, val token: String, val locale: String, val expiresAt: Instant) {
    override fun toString(): String = "VerificationEmail([redacted])"
}

class VerificationPayloadDecryptor(
    private val paths: List<Path>,
    private val mapper: ObjectMapper,
    private val clock: Clock,
    requireAtStartup: Boolean,
) {
    private var loadedAt: Instant? = null
    private var keys: Map<String, RSAKey> = emptyMap()
    private var completeKeyRing = false
    init {
        if (requireAtStartup) {
            require(paths.isNotEmpty() && paths.all(Files::isRegularFile)) { "All Notification decryption keys must be mounted" }
            try { reload() } catch (_: Exception) { throw IllegalArgumentException("Invalid Notification decryption key material") }
            require(completeKeyRing) { "All configured decryption keys must be valid and have distinct ids" }
        }
    }

    @Synchronized
    fun decrypt(compact: String, attemptId: UUID, expiresAt: Instant): VerificationEmail {
        if (loadedAt == null || loadedAt!!.plusSeconds(5).isBefore(clock.instant())) {
            try { reload() } catch (_: Exception) { throw DecryptionKeyUnavailableException() }
        }
        val jwe = try {
            require(compact.length in 1..32_768)
            JWEObject.parse(compact).also {
                require(it.header.algorithm == JWEAlgorithm.RSA_OAEP_256)
                require(it.header.encryptionMethod == EncryptionMethod.A256GCM)
                require(it.header.contentType == "application/json")
                require(!it.header.keyID.isNullOrBlank() && it.header.keyID.length <= 128)
                require(it.header.compressionAlgorithm == null && it.header.criticalParams.isNullOrEmpty())
                require(it.header.jwk == null && it.header.getJWKURL() == null && it.header.x509CertURL == null)
            }
        } catch (_: Exception) { throw InvalidVerificationPayloadException() }
        val key = keys[jwe.header.keyID] ?: throw DecryptionKeyUnavailableException()
        return try {
            jwe.decrypt(RSADecrypter(key))
            require(jwe.payload.toBytes().size <= 16_384)
            val body = mapper.readTree(jwe.payload.toString())
            require(UUID.fromString(body.path("registrationAttemptId").stringValue()) == attemptId)
            val expiry = Instant.parse(body.path("expiresAt").stringValue())
            require(expiry == expiresAt && expiry.isAfter(clock.instant()))
            val recipient = body.path("recipientEmail").stringValue()
            require(recipient.length in 3..320 && recipient.none(Char::isISOControl))
            val token = body.path("token").stringValue()
            require(token.matches(Regex("v1e\\.${Regex.escape(attemptId.toString())}\\.[0-9a-fA-F-]{36}\\.[A-Za-z0-9_-]{43}")))
            // Identity omits nullable locale fields; Jackson 3 rejects stringValue() on MissingNode.
            val locale = body.path("locale").takeIf { it.isString }?.stringValue()
                ?.substringBefore('-')?.lowercase()?.takeIf { it == "ru" || it == "en" } ?: "ru"
            VerificationEmail(recipient, token, locale, expiry)
        } catch (_: Exception) { throw InvalidVerificationPayloadException() }
    }

    @Synchronized
    fun ready(): Boolean {
        if (!completeKeyRing || loadedAt == null || !loadedAt!!.plusSeconds(5).isAfter(clock.instant())) {
            try { reload() } catch (_: Exception) { return false }
        }
        return completeKeyRing
    }

    private fun reload() {
        val loaded = paths.map { path -> runCatching {
            require(Files.isRegularFile(path)) { "All Notification decryption keys must be mounted" }
            require(Files.size(path) <= 65_536) { "Notification key file is too large" }
            RSAKey.parse(Files.readString(path)).also {
                require(it.isPrivate && it.toRSAPublicKey().modulus.bitLength() >= 2048)
                require(it.keyUse == KeyUse.ENCRYPTION && it.algorithm == JWEAlgorithm.RSA_OAEP_256)
                require(!it.keyID.isNullOrBlank() && it.keyID.length <= 128)
                it.toRSAPrivateKey()
                val probe = JWEObject(JWEHeader(JWEAlgorithm.RSA_OAEP_256, EncryptionMethod.A256GCM), Payload("key-self-test"))
                probe.encrypt(RSAEncrypter(it.toPublicJWK()))
                probe.decrypt(RSADecrypter(it))
                require(probe.payload.toString() == "key-self-test")
            }
        } }
        val byId = loaded.mapNotNull { it.getOrNull() }.groupBy { it.keyID }
        // Never keep stale material for a lost mount, or choose between duplicate
        // key ids. A broken retiring key must not suppress healthy independent keys.
        keys = byId.filterValues { it.size == 1 }.mapValues { it.value.single() }
        completeKeyRing = paths.isNotEmpty() && loaded.all { it.isSuccess } && keys.size == paths.size
        loadedAt = clock.instant()
    }
}
