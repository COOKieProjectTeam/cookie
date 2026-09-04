package com.cookie.identity.config

import com.cookie.identity.application.ConfirmEmailHandler
import com.cookie.identity.application.GetIdentityJwksHandler
import com.cookie.identity.application.IdentityPolicy
import com.cookie.identity.application.IdentityRateLimiter
import com.cookie.identity.application.LoginWithEmailHandler
import com.cookie.identity.application.LogoutHandler
import com.cookie.identity.application.RefreshSessionHandler
import com.cookie.identity.application.RegistrationAttemptIssuer
import com.cookie.identity.application.RegisterWithEmailHandler
import com.cookie.identity.application.ResendEmailVerificationHandler
import com.cookie.identity.application.SessionIssuer
import com.cookie.identity.application.ports.AccessTokenProvider
import com.cookie.identity.application.ports.AccountRepository
import com.cookie.identity.application.ports.CurrentTimeProvider
import com.cookie.identity.application.ports.IdGenerator
import com.cookie.identity.application.ports.IdentityEventRecorder
import com.cookie.identity.application.ports.PasswordHashing
import com.cookie.identity.application.ports.RateLimitRepository
import com.cookie.identity.application.ports.RefreshFamilyRepository
import com.cookie.identity.application.ports.RefreshTokenService
import com.cookie.identity.application.ports.RegistrationAttemptRepository
import com.cookie.identity.application.ports.RegistrationSecretService
import com.cookie.identity.application.ports.TransactionRunner
import com.cookie.identity.domain.PasswordPolicy
import com.cookie.identity.domain.RussianEmailAdmissionPolicy
import com.cookie.platform.web.RequestIdFilter
import com.cookie.platform.web.BoundedRequestBodyFilter
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import java.security.SecureRandom
import java.time.Clock

@Configuration
@EnableScheduling
class IdentityConfiguration {
    @Bean
    fun clock(): Clock = Clock.systemUTC()

    @Bean
    fun secureRandom(): SecureRandom = SecureRandom()

    @Bean
    fun passwordPolicy() = PasswordPolicy()

    @Bean
    fun emailAdmissionPolicy() = RussianEmailAdmissionPolicy()

    @Bean
    fun identityPolicy(properties: IdentityProperties) = IdentityPolicy(
        refreshFamilyTtl = properties.refreshFamilyTtl,
        registrationAttemptTtl = properties.registrationAttemptTtl,
        verificationTokenTtl = properties.verificationTokenTtl,
        verificationResendCooldown = properties.verificationResendCooldown,
    )

    @Bean
    fun identityRateLimiter(repository: RateLimitRepository) = IdentityRateLimiter(repository)

    @Bean
    fun registrationAttemptIssuer(
        attempts: RegistrationAttemptRepository,
        registrationSecrets: RegistrationSecretService,
        ids: IdGenerator,
        events: IdentityEventRecorder,
        policy: IdentityPolicy,
    ) = RegistrationAttemptIssuer(attempts, registrationSecrets, ids, events, policy)

    @Bean
    fun sessionIssuer(
        families: RefreshFamilyRepository,
        refreshTokens: RefreshTokenService,
        ids: IdGenerator,
        accessTokens: AccessTokenProvider,
        policy: IdentityPolicy,
    ) = SessionIssuer(families, refreshTokens, ids, accessTokens, policy)

    @Bean
    fun registerWithEmailHandler(
        accounts: AccountRepository,
        attempts: RegistrationAttemptRepository,
        transactions: TransactionRunner,
        emailAdmissionPolicy: RussianEmailAdmissionPolicy,
        passwordPolicy: PasswordPolicy,
        passwordHashing: PasswordHashing,
        registrationSecrets: RegistrationSecretService,
        rateLimiter: IdentityRateLimiter,
        attemptIssuer: RegistrationAttemptIssuer,
        currentTime: CurrentTimeProvider,
    ) = RegisterWithEmailHandler(
        accounts,
        attempts,
        transactions,
        emailAdmissionPolicy,
        passwordPolicy,
        passwordHashing,
        registrationSecrets,
        rateLimiter,
        attemptIssuer,
        currentTime,
    )

    @Bean
    fun resendEmailVerificationHandler(
        accounts: AccountRepository,
        attempts: RegistrationAttemptRepository,
        transactions: TransactionRunner,
        rateLimiter: IdentityRateLimiter,
        registrationSecrets: RegistrationSecretService,
        attemptIssuer: RegistrationAttemptIssuer,
        policy: IdentityPolicy,
        currentTime: CurrentTimeProvider,
    ) = ResendEmailVerificationHandler(
        accounts,
        attempts,
        transactions,
        rateLimiter,
        registrationSecrets,
        attemptIssuer,
        policy,
        currentTime,
    )

    @Bean
    fun confirmEmailHandler(
        accounts: AccountRepository,
        attempts: RegistrationAttemptRepository,
        transactions: TransactionRunner,
        registrationSecrets: RegistrationSecretService,
        ids: IdGenerator,
        rateLimiter: IdentityRateLimiter,
        events: IdentityEventRecorder,
        currentTime: CurrentTimeProvider,
    ) = ConfirmEmailHandler(
        accounts,
        attempts,
        transactions,
        registrationSecrets,
        ids,
        rateLimiter,
        events,
        currentTime,
    )

    @Bean
    fun loginWithEmailHandler(
        accounts: AccountRepository,
        transactions: TransactionRunner,
        passwordPolicy: PasswordPolicy,
        passwordHashing: PasswordHashing,
        rateLimiter: IdentityRateLimiter,
        sessionIssuer: SessionIssuer,
        currentTime: CurrentTimeProvider,
    ) = LoginWithEmailHandler(
        accounts,
        transactions,
        passwordPolicy,
        passwordHashing,
        rateLimiter,
        sessionIssuer,
        currentTime,
    )

    @Bean
    fun refreshSessionHandler(
        families: RefreshFamilyRepository,
        transactions: TransactionRunner,
        refreshTokens: RefreshTokenService,
        rateLimiter: IdentityRateLimiter,
        sessionIssuer: SessionIssuer,
        currentTime: CurrentTimeProvider,
    ) = RefreshSessionHandler(families, transactions, refreshTokens, rateLimiter, sessionIssuer, currentTime)

    @Bean
    fun logoutHandler(
        families: RefreshFamilyRepository,
        transactions: TransactionRunner,
        refreshTokens: RefreshTokenService,
        rateLimiter: IdentityRateLimiter,
        currentTime: CurrentTimeProvider,
    ) = LogoutHandler(families, transactions, refreshTokens, rateLimiter, currentTime)

    @Bean
    fun getIdentityJwksHandler(accessTokens: AccessTokenProvider) = GetIdentityJwksHandler(accessTokens)

    @Bean
    fun requestIdFilter(): FilterRegistrationBean<RequestIdFilter> =
        FilterRegistrationBean(RequestIdFilter()).apply {
            order = Int.MIN_VALUE
        }

    @Bean
    fun requestBodyLimitFilter(): FilterRegistrationBean<BoundedRequestBodyFilter> =
        FilterRegistrationBean(BoundedRequestBodyFilter(MAX_REQUEST_BODY_BYTES)).apply {
            order = Int.MIN_VALUE + 1
        }

    private companion object {
        const val MAX_REQUEST_BODY_BYTES = 16 * 1_024
    }
}
