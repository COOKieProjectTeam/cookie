package com.cookie.user.security

import com.cookie.platform.web.BoundedHttpExchange
import com.cookie.user.domain.UserFailure
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.jwk.*
import com.nimbusds.jwt.SignedJWT
import org.springframework.scheduling.annotation.Scheduled
import java.net.URI
import java.net.http.HttpRequest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Fixed-origin, bounded public key discovery. No token-supplied key or URL is trusted. */
class IdentityTokens(private val source: () -> String, private val issuer: String,
                     private val audience: String, private val clock: Clock) {
    private data class Cache(val keys: Map<String,ECKey>,val expires: Instant)
    @Volatile private var cache: Cache? = null
    private var attempted: Instant? = null
    @Volatile private var refreshSucceeded=false
    @Synchronized
    @Scheduled(fixedDelay=240_000)
    fun refresh() {
        val now=clock.instant()
        if(attempted?.plusSeconds(30)?.isAfter(now)==true) return
        attempted=now
        try {
            val keys=JWKSet.parse(source()).keys
            require(keys.size in 1..16)
            val accepted=keys.map {
                require(it is ECKey && !it.isPrivate && it.curve==Curve.P_256 && it.algorithm==JWSAlgorithm.ES256 && it.keyUse==KeyUse.SIGNATURE)
                require(!it.keyID.isNullOrBlank() && it.keyID.length<=128)
                it.toECPublicKey(); it
            }
            require(accepted.map { it.keyID }.distinct().size==accepted.size)
            cache=Cache(accepted.associateBy { it.keyID },now.plusSeconds(300));refreshSucceeded=true
        } catch (_:Exception) { refreshSucceeded=false }
    }
    fun ready()=cache?.expires?.isAfter(clock.instant())==true
    fun verify(token: String): UUID {
        val parsed=try {
            require(token.length in 1..8192 && token.none(Char::isWhitespace))
            SignedJWT.parse(token).also {
                require(it.header.type==JOSEObjectType("at+jwt") && it.header.algorithm==JWSAlgorithm.ES256)
                require(it.header.criticalParams.isNullOrEmpty() && it.header.jwk==null && it.header.getJWKURL()==null && it.header.x509CertURL==null)
                require(!it.header.keyID.isNullOrBlank() && it.header.keyID.length<=128)
            }
        } catch (_:Exception) { throw UserFailure("INVALID_ACCESS_TOKEN",401) }
        val now=clock.instant()
        if(!ready() || parsed.header.keyID !in cache!!.keys) refresh()
        val current=cache?.takeIf { it.expires.isAfter(now) } ?: throw UserFailure("AUTHENTICATION_UNAVAILABLE",503)
        val key=current.keys[parsed.header.keyID] ?: throw UserFailure(if(refreshSucceeded) "INVALID_ACCESS_TOKEN" else "AUTHENTICATION_UNAVAILABLE",if(refreshSucceeded) 401 else 503)
        return try {
            require(parsed.verify(ECDSAVerifier(key)))
            val c=parsed.jwtClaimsSet
            require(c.issuer==issuer && c.audience==listOf(audience))
            val issued=requireNotNull(c.issueTime).toInstant();val expires=requireNotNull(c.expirationTime).toInstant()
            require(!issued.isAfter(now) && expires.isAfter(now) && expires.isAfter(issued) && Duration.between(issued,expires)<=Duration.ofHours(24))
            c.notBeforeTime?.let { require(!it.toInstant().isAfter(now)) }
            uuid(requireNotNull(c.getStringClaim("sid")));uuid(requireNotNull(c.getJWTID()))
            uuid(requireNotNull(c.subject))
        } catch (_:Exception) { throw UserFailure("INVALID_ACCESS_TOKEN",401) }
    }
    private fun uuid(raw: String)=UUID.fromString(raw).also { require(it.toString().equals(raw,true) && it.version()==7 && it.variant()==2) }
    companion object {
        fun httpSource(uri: URI,http: BoundedHttpExchange): () -> String = {
            val response=http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(3)).GET().build(),Duration.ofSeconds(3))
            check(response.statusCode()==200)
            response.body().toString(Charsets.UTF_8)
        }
    }
}
