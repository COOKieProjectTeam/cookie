package com.cookie.identity.application

import com.cookie.identity.application.ports.AccountRepository
import com.cookie.identity.application.ports.CurrentTimeProvider
import com.cookie.identity.application.ports.PasswordHashing
import com.cookie.identity.application.ports.RegisterWithEmailUseCase
import com.cookie.identity.application.ports.RegistrationAttemptRepository
import com.cookie.identity.application.ports.RegistrationSecretService
import com.cookie.identity.application.ports.ResendEmailVerificationUseCase
import com.cookie.identity.application.ports.TransactionRunner
import com.cookie.identity.domain.CanonicalEmail
import com.cookie.identity.domain.LocaleTag
import com.cookie.identity.domain.PasswordPolicy
import com.cookie.identity.domain.RegistrationProof
import com.cookie.identity.domain.RegistrationRequestDecision
import com.cookie.identity.domain.RussianEmailAdmissionPolicy
import java.util.UUID

class RegisterWithEmailHandler(
    private val accounts: AccountRepository,
    private val attempts: RegistrationAttemptRepository,
    private val transactions: TransactionRunner,
    private val emailAdmissionPolicy: RussianEmailAdmissionPolicy,
    private val passwordPolicy: PasswordPolicy,
    private val passwordHashing: PasswordHashing,
    private val registrationSecrets: RegistrationSecretService,
    private val rateLimiter: IdentityRateLimiter,
    private val attemptIssuer: RegistrationAttemptIssuer,
    private val currentTime: CurrentTimeProvider,
) : RegisterWithEmailUseCase {
    override fun execute(
        registrationAttemptId: UUID,
        rawEmail: String,
        password: String,
        registrationProof: String,
        locale: String?,
        ip: String,
    ) {
        // The coarse application abuse ceiling is deliberately first: decoded
        // domain-invalid and exact-replay traffic must not reach domain parsing
        // or aggregate storage for free. Invalid JSON is rejected by transport
        // before this use case and must also be limited at ingress.
        rateLimiter.registerIp(ip)
        val localeTag = LocaleTag.parseOrNull(locale)
        val email = CanonicalEmail.parse(rawEmail)
        emailAdmissionPolicy.validate(email)
        val normalizedPassword = passwordPolicy.prepareForRegistration(password)
        val proof = RegistrationProof.parse(registrationProof)
        val proofHash = registrationSecrets.hashRegistrationProof(proof)
        val requestFingerprint = registrationSecrets.registrationRequestFingerprint(
            proof,
            registrationAttemptId,
            email,
            normalizedPassword,
            localeTag,
        )

        val observed = attempts.findById(registrationAttemptId)
        val observedDecision = observed
            ?.takeIf { it.email == email }
            ?.takeIf { registrationSecrets.verifierMatches(it.registrationProofHash, proofHash) }
            ?.requestDecision(requestFingerprint, currentTime.now())
        if (observedDecision.isIdempotentNoOp()) {
            return
        }

        rateLimiter.registerEmail(email.value)
        val passwordHash = passwordHashing.encode(normalizedPassword.value)

        transactions.requiredUnit {
            accounts.lockRegistration(email)
            attempts.lockCreationKeys(registrationAttemptId, proofHash)
            val now = currentTime.now()
            val byId = attempts.findByIdForUpdate(registrationAttemptId)
            if (byId != null) {
                val decision = byId
                    .takeIf { it.email == email }
                    ?.takeIf { registrationSecrets.verifierMatches(it.registrationProofHash, proofHash) }
                    ?.requestDecision(requestFingerprint, now)
                if (decision.isIdempotentNoOp()) {
                    return@requiredUnit
                }
                throw RegistrationAttemptConflictException()
            }
            if (attempts.findByProof(proofHash) != null) {
                throw RegistrationAttemptConflictException()
            }
            if (accounts.findByEmailForUpdate(email) != null) return@requiredUnit
            attemptIssuer.start(
                attemptId = registrationAttemptId,
                email = email,
                pendingPasswordHash = passwordHash,
                registrationProofHash = proofHash,
                requestFingerprint = requestFingerprint,
                locale = localeTag,
                now = now,
            )
        }
    }
}

private fun RegistrationRequestDecision?.isIdempotentNoOp(): Boolean =
    this == RegistrationRequestDecision.EXACT_RETRY ||
        this == RegistrationRequestDecision.EXPIRED ||
        this == RegistrationRequestDecision.ABANDONED

class ResendEmailVerificationHandler(
    private val accounts: AccountRepository,
    private val attempts: RegistrationAttemptRepository,
    private val transactions: TransactionRunner,
    private val rateLimiter: IdentityRateLimiter,
    private val registrationSecrets: RegistrationSecretService,
    private val attemptIssuer: RegistrationAttemptIssuer,
    private val policy: IdentityPolicy,
    private val currentTime: CurrentTimeProvider,
) : ResendEmailVerificationUseCase {
    override fun execute(registrationAttemptId: UUID, rawEmail: String, registrationProof: String, ip: String) {
        rateLimiter.resendIp(ip)
        val email = CanonicalEmail.parse(rawEmail)
        val proofHash = registrationSecrets.hashRegistrationProof(RegistrationProof.parse(registrationProof))
        val observed = attempts.findById(registrationAttemptId)
        val now = currentTime.now()
        if (
            observed != null &&
            observed.email == email &&
            registrationSecrets.verifierMatches(observed.registrationProofHash, proofHash) &&
            (observed.isCompleted ||
                !observed.expiresAt.isAfter(now) ||
                !observed.canIssueVerificationToken(now, policy.verificationResendCooldown))
        ) {
            return
        }

        rateLimiter.resendEmail(email.value)
        if (observed == null || observed.email != email) return
        if (!registrationSecrets.verifierMatches(observed.registrationProofHash, proofHash)) return

        transactions.requiredUnit {
            accounts.lockRegistration(email)
            if (accounts.findByEmailForUpdate(email) != null) return@requiredUnit
            val attempt = attempts.findByIdForUpdate(registrationAttemptId) ?: return@requiredUnit
            if (attempt.email != email) return@requiredUnit
            if (!registrationSecrets.verifierMatches(attempt.registrationProofHash, proofHash)) return@requiredUnit
            val transactionNow = currentTime.now()
            if (!attempt.canIssueVerificationToken(transactionNow, policy.verificationResendCooldown)) {
                return@requiredUnit
            }
            attemptIssuer.issueVerificationToken(attempt, transactionNow)
        }
    }
}
