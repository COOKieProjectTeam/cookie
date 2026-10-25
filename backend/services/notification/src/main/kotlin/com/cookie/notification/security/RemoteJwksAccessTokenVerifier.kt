package com.cookie.notification.security

import com.cookie.platform.web.BoundedHttpExchange
import com.cookie.platform.web.security.BoundedJwksSource
import com.cookie.platform.web.security.IdentityAccessTokenVerifier
import com.cookie.platform.web.security.IdentityAuthenticationUnavailable
import com.cookie.platform.web.security.InvalidIdentityAccessToken
import org.springframework.scheduling.annotation.Scheduled
import java.net.URI
import java.time.Clock
import java.util.UUID

class InvalidAccessTokenException : RuntimeException("Invalid access token")
class AuthenticationUnavailableException : RuntimeException("Authentication key source unavailable")

fun interface JwksSource { fun fetch(): String }

class HttpJwksSource(uri: URI, http: BoundedHttpExchange) : JwksSource {
    private val source = BoundedJwksSource(uri, http)
    override fun fetch() = source.fetch()
}

/** Notification error mapping around the same Identity verification policy used by User. */
class RemoteJwksAccessTokenVerifier(source: JwksSource, issuer: String, audience: String, clock: Clock) {
    private val verifier = IdentityAccessTokenVerifier(source::fetch, issuer, audience, clock)

    fun verify(rawToken: String): UUID = try {
        verifier.verify(rawToken)
    } catch (_: InvalidIdentityAccessToken) {
        throw InvalidAccessTokenException()
    } catch (_: IdentityAuthenticationUnavailable) {
        throw AuthenticationUnavailableException()
    }

    fun ready() = verifier.ready()
    @Scheduled(fixedDelay = 240_000, initialDelay = 0)
    fun refreshScheduled() = refresh()
    fun refresh() = verifier.refresh()
}
