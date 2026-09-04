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
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import java.time.Duration
import java.util.UUID

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
    private val clientIpResolver: ClientIpResolver,
) : AuthApi {
    override fun registerWithEmail(emailRegistrationRequest: EmailRegistrationRequest): ResponseEntity<Unit> {
        registerWithEmail.execute(
            emailRegistrationRequest.registrationAttemptId,
            emailRegistrationRequest.email,
            emailRegistrationRequest.password,
            emailRegistrationRequest.registrationProof,
            emailRegistrationRequest.locale,
            clientIp(),
        )
        return ResponseEntity.accepted().build()
    }

    override fun resendEmailVerification(emailActionRequest: EmailActionRequest): ResponseEntity<Unit> {
        resendEmailVerification.execute(
            emailActionRequest.registrationAttemptId,
            emailActionRequest.email,
            emailActionRequest.registrationProof,
            clientIp(),
        )
        return ResponseEntity.accepted().build()
    }

    override fun confirmEmail(
        emailVerificationConfirmRequest: EmailVerificationConfirmRequest,
    ): ResponseEntity<Unit> {
        confirmEmail.execute(
            emailVerificationConfirmRequest.token,
            emailVerificationConfirmRequest.registrationProof,
            clientIp(),
        )
        return ResponseEntity.noContent().build()
    }

    override fun loginWithEmail(emailLoginRequest: EmailLoginRequest): ResponseEntity<TokenPairWithUser> =
        secretResponse(
            loginWithEmail.execute(
                emailLoginRequest.email,
                emailLoginRequest.password,
                emailLoginRequest.deviceId,
                clientIp(),
            ).toTransportWithUser(),
        )

    override fun refreshSession(
        idempotencyKey: UUID,
        refreshTokenRequest: RefreshTokenRequest,
    ): ResponseEntity<TokenPair> =
        secretResponse(
            refreshSession.execute(refreshTokenRequest.refreshToken, idempotencyKey, clientIp()).toTransport(),
        )

    override fun logout(refreshTokenRequest: RefreshTokenRequest): ResponseEntity<Unit> {
        logout.execute(refreshTokenRequest.refreshToken, clientIp())
        return ResponseEntity.noContent().build()
    }

    override fun getIdentityJwks(): ResponseEntity<JsonWebKeySet> = ResponseEntity.ok()
        .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
        .body(JsonWebKeySet(propertyKeys = getIdentityJwks.execute().map { it.toTransport() }))

    private fun clientIp(): String = clientIpResolver.resolve(request)

    /** Token-bearing responses must never be stored by browsers or intermediaries. */
    private fun <T : Any> secretResponse(body: T): ResponseEntity<T> = ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.PRAGMA, "no-cache")
        .body(body)

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
        user = AuthenticatedUser(accountId),
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
