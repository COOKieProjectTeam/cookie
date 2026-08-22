package com.cookie.identity.transport

import com.cookie.identity.application.IssuedTokens
import com.cookie.identity.application.PublicJwk
import com.cookie.identity.application.ports.ConfirmEmailUseCase
import com.cookie.identity.application.ports.GetIdentityJwksUseCase
import com.cookie.identity.application.ports.LoginWithEmailUseCase
import com.cookie.identity.application.ports.LogoutUseCase
import com.cookie.identity.application.ports.RefreshSessionUseCase
import com.cookie.identity.application.ports.RegisterWithEmailUseCase
import com.cookie.identity.application.ports.ResendEmailVerificationUseCase
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
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import java.time.Duration

@Controller
class AuthController(
    private val registerWithEmail: RegisterWithEmailUseCase,
    private val resendEmailVerification: ResendEmailVerificationUseCase,
    private val confirmEmail: ConfirmEmailUseCase,
    private val loginWithEmail: LoginWithEmailUseCase,
    private val refreshSession: RefreshSessionUseCase,
    private val logout: LogoutUseCase,
    private val getIdentityJwks: GetIdentityJwksUseCase,
    private val request: HttpServletRequest,
) : AuthApi {
    override fun registerWithEmail(emailRegistrationRequest: EmailRegistrationRequest): ResponseEntity<Unit> {
        registerWithEmail.execute(
            emailRegistrationRequest.email,
            emailRegistrationRequest.password,
            emailRegistrationRequest.locale,
            clientIp(),
        )
        return ResponseEntity.accepted().build()
    }

    override fun resendEmailVerification(emailActionRequest: EmailActionRequest): ResponseEntity<Unit> {
        resendEmailVerification.execute(emailActionRequest.email, clientIp())
        return ResponseEntity.accepted().build()
    }

    override fun confirmEmail(
        emailVerificationConfirmRequest: EmailVerificationConfirmRequest,
    ): ResponseEntity<TokenPairWithUser> = ResponseEntity.ok(
        confirmEmail.execute(
            emailVerificationConfirmRequest.token,
            emailVerificationConfirmRequest.deviceId,
            clientIp(),
        ).toTransportWithUser(),
    )

    override fun loginWithEmail(emailLoginRequest: EmailLoginRequest): ResponseEntity<TokenPairWithUser> =
        ResponseEntity.ok(
            loginWithEmail.execute(
                emailLoginRequest.email,
                emailLoginRequest.password,
                emailLoginRequest.deviceId,
                clientIp(),
            ).toTransportWithUser(),
        )

    override fun refreshSession(refreshTokenRequest: RefreshTokenRequest): ResponseEntity<TokenPair> =
        ResponseEntity.ok(
            refreshSession.execute(refreshTokenRequest.refreshToken, clientIp()).toTransport(),
        )

    override fun logout(refreshTokenRequest: RefreshTokenRequest): ResponseEntity<Unit> {
        logout.execute(refreshTokenRequest.refreshToken, clientIp())
        return ResponseEntity.noContent().build()
    }

    override fun getIdentityJwks(): ResponseEntity<JsonWebKeySet> = ResponseEntity.ok()
        .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
        .body(JsonWebKeySet(propertyKeys = getIdentityJwks.execute().map { it.toTransport() }))

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

    private fun PublicJwk.toTransport() = com.cookie.identity.generated.model.JsonWebKey(
        kty = com.cookie.identity.generated.model.JsonWebKey.Kty.EC,
        use = com.cookie.identity.generated.model.JsonWebKey.Use.SIG,
        alg = com.cookie.identity.generated.model.JsonWebKey.Alg.ES256,
        kid = keyId,
        crv = com.cookie.identity.generated.model.JsonWebKey.Crv.P_256,
        x = x,
        y = y,
    )
}
