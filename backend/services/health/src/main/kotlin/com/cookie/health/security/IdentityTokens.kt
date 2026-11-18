package com.cookie.health.security

import com.cookie.platform.web.BoundedHttpExchange
import com.cookie.platform.web.security.BoundedJwksSource
import com.cookie.platform.web.security.IdentityAccessTokenVerifier
import com.cookie.platform.web.security.IdentityAuthenticationUnavailable
import com.cookie.platform.web.security.InvalidIdentityAccessToken
import com.cookie.health.HealthFailure
import org.springframework.scheduling.annotation.Scheduled
import java.net.URI
import java.time.Clock
import java.util.UUID

/** Service-local errors around the shared fixed-origin Identity token policy. */
class IdentityTokens(source: () -> String, issuer: String, audience: String, clock: Clock) {
    private val verifier = IdentityAccessTokenVerifier(source, issuer, audience, clock,
        maximumTokenLength = 8192, rejectWhitespace = true)

    @Scheduled(fixedDelay = 240_000)
    fun refresh() = verifier.refresh()
    fun ready() = verifier.ready()
    fun verify(token: String): UUID = try {
        verifier.verify(token)
    } catch (_: InvalidIdentityAccessToken) {
        throw HealthFailure("INVALID_ACCESS_TOKEN", 401)
    } catch (_: IdentityAuthenticationUnavailable) {
        throw HealthFailure("AUTHENTICATION_UNAVAILABLE", 503)
    }

    companion object {
        fun httpSource(uri: URI, http: BoundedHttpExchange): () -> String = BoundedJwksSource(uri, http)::fetch
    }
}
