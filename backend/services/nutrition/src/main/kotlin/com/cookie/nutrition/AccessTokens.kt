package com.cookie.nutrition

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jwt.SignedJWT
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import tools.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

/** Public-only mounted JWKS is an explicit standalone alternative to PR #20's remote adapter. */
@Component
class AccessTokens(private val properties: NutritionProperties) {
    fun keys(): Map<String,ECKey> = try {
        val path=Path.of(properties.jwksPath)
        val bytes=Files.newInputStream(path).use { it.readNBytes(65537) }
        require(bytes.size<=65536)
        val keys=JWKSet.parse(bytes.toString(Charsets.UTF_8)).keys
        require(keys.size in 1..16)
        val public=keys.map {
            require(it is ECKey && !it.isPrivate && it.curve==Curve.P_256 && it.algorithm==JWSAlgorithm.ES256 && it.keyUse==KeyUse.SIGNATURE && !it.keyID.isNullOrBlank())
            it
        }
        require(public.map { it.keyID }.distinct().size==public.size)
        public.associateBy { it.keyID }
    } catch (_: Exception) { throw NutritionFailure(503,"AUTHENTICATION_UNAVAILABLE") }
    fun verify(raw: String, now: Instant = Instant.now()): UUID {
        val token=try {
            require(raw.length in 1..16384)
            SignedJWT.parse(raw).also {
                require(it.header.algorithm==JWSAlgorithm.ES256 && it.header.type==JOSEObjectType("at+jwt"))
                require(it.header.criticalParams.isNullOrEmpty() && it.header.jwk==null && it.header.jwkurl==null && it.header.x509CertURL==null)
            }
        } catch (_: Exception) { throw NutritionFailure(401,"UNAUTHORIZED") }
        val keys=keys()
        return try {
            val key=keys[token.header.keyID] ?: error("unknown key")
            require(token.verify(ECDSAVerifier(key.toECPublicKey())))
            val c=token.jwtClaimsSet
            require(c.issuer==properties.issuer && c.audience==listOf(properties.audience))
            val issued=requireNotNull(c.issueTime).toInstant();val expires=requireNotNull(c.expirationTime).toInstant()
            require(!issued.isAfter(now) && expires.isAfter(now) && expires.isAfter(issued))
            c.notBeforeTime?.let { require(!it.toInstant().isAfter(now)) }
            fun uuid(rawId: String): UUID = UUID.fromString(rawId).also { require(it.version()==7 && it.variant()==2 && it.toString()==rawId.lowercase()) }
            uuid(requireNotNull(c.jwtid));uuid(requireNotNull(c.getStringClaim("sid")));uuid(requireNotNull(c.subject))
        } catch (_: Exception) { throw NutritionFailure(401,"UNAUTHORIZED") }
    }
}
class AuthenticationFilter(private val tokens: AccessTokens,private val mapper: ObjectMapper): OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest) = request.requestURI in setOf("/healthz","/readyz")
    override fun doFilterInternal(request: HttpServletRequest,response: HttpServletResponse,chain: FilterChain) {
        try {
            val raw=request.getHeader("Authorization")?.takeIf { it.startsWith("Bearer ") }?.substring(7) ?: throw NutritionFailure(401,"UNAUTHORIZED")
            request.setAttribute("nutrition.user",tokens.verify(raw))
            if(request.method in setOf("POST","PATCH")) {
                val body=try { mapper.readTree(request.inputStream) } catch (_: Exception) { invalid() }
                if(body==null || !body.isObject) invalid()
                request.setAttribute("nutrition.body",body)
            }
        } catch (e: NutritionFailure) {
            response.status=e.status;response.contentType="application/json"
            if(e.status==401) response.setHeader("WWW-Authenticate","Bearer")
            response.writer.write("{\"code\":\"${e.code}\",\"message\":\"${e.code}\"}");return
        }
        chain.doFilter(request,response)
    }
}
