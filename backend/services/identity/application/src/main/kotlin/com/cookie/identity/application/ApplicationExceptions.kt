package com.cookie.identity.application

import com.cookie.identity.domain.IdentityException

class InvalidCredentialsException : IdentityException("Invalid credentials")

open class InvalidTokenException : IdentityException("Invalid or expired token")

class InvalidActionTokenException : IdentityException("Invalid or expired verification token")

class RateLimitExceededException(val retryAfterSeconds: Long) : IdentityException("Rate limit exceeded")

class IdentityUnavailableException(message: String) : IdentityException(message)
