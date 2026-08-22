package com.cookie.identity.config

import com.cookie.identity.application.ConfirmEmailHandler
import com.cookie.identity.application.GetIdentityJwksHandler
import com.cookie.identity.application.IdentityPolicy
import com.cookie.identity.application.IdentityRateLimiter
import com.cookie.identity.application.LoginWithEmailHandler
import com.cookie.identity.application.LogoutHandler
import com.cookie.identity.application.RefreshSessionHandler
import com.cookie.identity.application.RegisterWithEmailHandler
import com.cookie.identity.application.ResendEmailVerificationHandler
import com.cookie.identity.application.SessionIssuer
import com.cookie.identity.application.VerificationChallengeIssuer
import com.cookie.identity.application.ports.AccessTokenProvider
import com.cookie.identity.application.ports.AccountRepository
import com.cookie.identity.application.ports.IdGenerator
import com.cookie.identity.application.ports.IdentityEventRecorder
import com.cookie.identity.application.ports.PasswordHashing
import com.cookie.identity.application.ports.RateLimitRepository
import com.cookie.identity.application.ports.RefreshSessionRepository
import com.cookie.identity.application.ports.SecretTokenService
import com.cookie.identity.application.ports.TransactionRunner
import com.cookie.identity.application.ports.VerificationChallengeRepository
import com.cookie.identity.domain.PasswordPolicy
import com.cookie.platform.web.RequestIdFilter
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
    fun identityPolicy(properties: IdentityProperties) = IdentityPolicy(
        refreshFamilyTtl = properties.refreshFamilyTtl,
        verificationTokenTtl = properties.verificationTokenTtl,
        verificationResendCooldown = properties.verificationResendCooldown,
    )

    @Bean
    fun identityRateLimiter(repository: RateLimitRepository, clock: Clock) =
        IdentityRateLimiter(repository, clock)

    @Bean
    fun verificationChallengeIssuer(
        challenges: VerificationChallengeRepository,
        secretTokens: SecretTokenService,
        ids: IdGenerator,
        events: IdentityEventRecorder,
        policy: IdentityPolicy,
    ) = VerificationChallengeIssuer(challenges, secretTokens, ids, events, policy)

    @Bean
    fun sessionIssuer(
        sessions: RefreshSessionRepository,
        secretTokens: SecretTokenService,
        ids: IdGenerator,
        accessTokens: AccessTokenProvider,
        policy: IdentityPolicy,
    ) = SessionIssuer(sessions, secretTokens, ids, accessTokens, policy)

    @Bean
    fun registerWithEmailHandler(
        accounts: AccountRepository,
        transactions: TransactionRunner,
        passwordPolicy: PasswordPolicy,
        passwordHashing: PasswordHashing,
        ids: IdGenerator,
        rateLimiter: IdentityRateLimiter,
        challengeIssuer: VerificationChallengeIssuer,
        clock: Clock,
    ) = RegisterWithEmailHandler(
        accounts,
        transactions,
        passwordPolicy,
        passwordHashing,
        ids,
        rateLimiter,
        challengeIssuer,
        clock,
    )

    @Bean
    fun resendEmailVerificationHandler(
        accounts: AccountRepository,
        challenges: VerificationChallengeRepository,
        transactions: TransactionRunner,
        rateLimiter: IdentityRateLimiter,
        challengeIssuer: VerificationChallengeIssuer,
        policy: IdentityPolicy,
        clock: Clock,
    ) = ResendEmailVerificationHandler(
        accounts,
        challenges,
        transactions,
        rateLimiter,
        challengeIssuer,
        policy,
        clock,
    )

    @Bean
    fun confirmEmailHandler(
        accounts: AccountRepository,
        challenges: VerificationChallengeRepository,
        transactions: TransactionRunner,
        secretTokens: SecretTokenService,
        rateLimiter: IdentityRateLimiter,
        events: IdentityEventRecorder,
        sessionIssuer: SessionIssuer,
        clock: Clock,
    ) = ConfirmEmailHandler(
        accounts,
        challenges,
        transactions,
        secretTokens,
        rateLimiter,
        events,
        sessionIssuer,
        clock,
    )

    @Bean
    fun loginWithEmailHandler(
        accounts: AccountRepository,
        transactions: TransactionRunner,
        passwordHashing: PasswordHashing,
        rateLimiter: IdentityRateLimiter,
        sessionIssuer: SessionIssuer,
        clock: Clock,
    ) = LoginWithEmailHandler(
        accounts,
        transactions,
        passwordHashing,
        rateLimiter,
        sessionIssuer,
        clock,
    )

    @Bean
    fun refreshSessionHandler(
        sessions: RefreshSessionRepository,
        transactions: TransactionRunner,
        secretTokens: SecretTokenService,
        rateLimiter: IdentityRateLimiter,
        sessionIssuer: SessionIssuer,
        clock: Clock,
    ) = RefreshSessionHandler(sessions, transactions, secretTokens, rateLimiter, sessionIssuer, clock)

    @Bean
    fun logoutHandler(
        sessions: RefreshSessionRepository,
        transactions: TransactionRunner,
        secretTokens: SecretTokenService,
        rateLimiter: IdentityRateLimiter,
        clock: Clock,
    ) = LogoutHandler(sessions, transactions, secretTokens, rateLimiter, clock)

    @Bean
    fun getIdentityJwksHandler(accessTokens: AccessTokenProvider) = GetIdentityJwksHandler(accessTokens)

    @Bean
    fun requestIdFilter(): FilterRegistrationBean<RequestIdFilter> =
        FilterRegistrationBean(RequestIdFilter()).apply {
            order = Int.MIN_VALUE
        }
}
