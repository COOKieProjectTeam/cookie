package com.cookie.platform.web.security

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jwt.SignedJWT
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration

class InvalidWorkloadToken : RuntimeException()
class WorkloadKeysUnavailable : RuntimeException()

class WorkloadTokenVerifier(private val issuer: URI, private val audience: String, private val jwks: Path, private val clock: Clock) {
    init {
        require(issuer.scheme == "https" && issuer.host != null && issuer.userInfo == null && issuer.fragment == null && issuer.query == null)
        require(audience.isNotBlank())
    }

    fun verify(token: String): String {
        if (token.length !in 1..8192 || token.any { it.isWhitespace() || it.isISOControl() }) throw InvalidWorkloadToken()
        val jwt = runCatching { SignedJWT.parse(token) }.getOrElse { throw InvalidWorkloadToken() }
        val header = jwt.header
        if (header.algorithm != JWSAlgorithm.ES256 || header.type != JOSEObjectType("workload+jwt") ||
            header.keyID.isNullOrBlank() || !header.criticalParams.isNullOrEmpty()
        ) throw InvalidWorkloadToken()
        val keys = readKeys()
        val key = keys.singleOrNull { it.keyID == header.keyID } ?: throw InvalidWorkloadToken()
        if (!runCatching { jwt.verify(ECDSAVerifier(key)) }.getOrDefault(false)) throw InvalidWorkloadToken()
        val claims = runCatching { jwt.jwtClaimsSet }.getOrElse { throw InvalidWorkloadToken() }
        val now = clock.instant()
        val issued = claims.issueTime?.toInstant() ?: throw InvalidWorkloadToken()
        val expires = claims.expirationTime?.toInstant() ?: throw InvalidWorkloadToken()
        val subject = claims.subject ?: throw InvalidWorkloadToken()
        if (claims.issuer != issuer.toString() || claims.audience != listOf(audience) ||
            !subject.matches(Regex("[a-z][a-z0-9_]{0,63}")) || issued > now.plusSeconds(30) || now >= expires ||
            Duration.between(issued, expires) !in Duration.ofSeconds(1)..Duration.ofMinutes(5) ||
            claims.notBeforeTime?.toInstant()?.let { it > now } == true
        ) throw InvalidWorkloadToken()
        return subject
    }

    private fun readKeys(): List<ECKey> = try {
        val body = Files.newInputStream(jwks).use { it.readNBytes(65_537) }
        require(body.size in 1..65_536)
        val keys = JWKSet.parse(body.decodeToString()).keys
        require(keys.size in 1..16 && keys.none { it.isPrivate })
        keys.filterIsInstance<ECKey>().filter {
            it.curve == Curve.P_256 && (it.keyUse == null || it.keyUse == KeyUse.SIGNATURE) &&
                (it.algorithm == null || it.algorithm == JWSAlgorithm.ES256) && !it.keyID.isNullOrBlank()
        }.also { require(it.isNotEmpty() && it.map(ECKey::getKeyID).distinct().size == it.size) }
    } catch (_: Exception) {
        throw WorkloadKeysUnavailable()
    }
}
