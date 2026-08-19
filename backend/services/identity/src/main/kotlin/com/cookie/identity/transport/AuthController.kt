package com.cookie.identity.transport

import com.cookie.identity.application.IdentityService
import com.cookie.identity.application.IssuedTokens
import com.cookie.identity.generated.api.AuthApi
import com.cookie.identity.generated.model.AuthenticatedUser
import com.cookie.identity.generated.model.EmailActionRequest
import com.cookie.identity.generated.model.EmailLoginRequest
import com.cookie.identity.generated.model.EmailRegistrationRequest
import com.cookie.identity.generated.model.EmailVerificationConfirmRequest
import com.cookie.identity.generated.model.JsonWebKeySet
import com.cookie.identity.generated.model.RefreshTokenRequest
import com.cookie.identity.generated.model.TokenPair
import com.cookie.identity.generated.model.TokenPairWithUser
import com.cookie.identity.security.AccessTokenIssuer
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import java.time.Duration

@Controller
class AuthController(
    private val identityService: IdentityService,
    private val accessTokenIssuer: AccessTokenIssuer,
    private val request: HttpServletRequest,
) : AuthApi {
    override fun registerWithEmail(emailRegistrationRequest: EmailRegistrationRequest): ResponseEntity<Unit> {
        identityService.register(
            emailRegistrationRequest.email,
            emailRegistrationRequest.password,
            emailRegistrationRequest.locale,
            clientIp(),
        )
        return ResponseEntity.accepted().build()
    }

    override fun resendEmailVerification(emailActionRequest: EmailActionRequest): ResponseEntity<Unit> {
        identityService.resend(emailActionRequest.email, clientIp())
        return ResponseEntity.accepted().build()
    }

    override fun confirmEmail(
        emailVerificationConfirmRequest: EmailVerificationConfirmRequest,
    ): ResponseEntity<TokenPairWithUser> = ResponseEntity.ok(
        identityService.confirm(
            emailVerificationConfirmRequest.token,
            emailVerificationConfirmRequest.deviceId,
            clientIp(),
        ).toTransportWithUser(),
    )

    override fun loginWithEmail(emailLoginRequest: EmailLoginRequest): ResponseEntity<TokenPairWithUser> =
        ResponseEntity.ok(
            identityService.login(
                emailLoginRequest.email,
                emailLoginRequest.password,
                emailLoginRequest.deviceId,
                clientIp(),
            ).toTransportWithUser(),
        )

    override fun refreshSession(refreshTokenRequest: RefreshTokenRequest): ResponseEntity<TokenPair> =
        ResponseEntity.ok(
            identityService.refresh(refreshTokenRequest.refreshToken, clientIp()).toTransport(),
        )

    override fun logout(refreshTokenRequest: RefreshTokenRequest): ResponseEntity<Unit> {
        identityService.logout(refreshTokenRequest.refreshToken, clientIp())
        return ResponseEntity.noContent().build()
    }

    override fun getIdentityJwks(): ResponseEntity<JsonWebKeySet> = ResponseEntity.ok()
        .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
        .body(accessTokenIssuer.jwks())

    private fun clientIp(): String = request.remoteAddr?.take(128) ?: "unknown"

    private fun IssuedTokens.toTransport() = TokenPair(
        accessToken = accessToken,
        accessTokenExpiresIn = accessTokenExpiresIn,
        refreshToken = refreshToken,
        refreshTokenExpiresIn = refreshTokenExpiresIn,
    )

    private fun IssuedTokens.toTransportWithUser() = TokenPairWithUser(
        accessToken = accessToken,
        accessTokenExpiresIn = accessTokenExpiresIn,
        refreshToken = refreshToken,
        refreshTokenExpiresIn = refreshTokenExpiresIn,
        user = AuthenticatedUser(accountId, newUser),
    )
}
