package com.cookie.identity.domain

open class IdentityException(message: String) : RuntimeException(message)

class InvalidInputException(message: String) : IdentityException(message)

class InvalidCredentialsException : IdentityException("Invalid credentials")

class InvalidTokenException : IdentityException("Invalid or expired token")

class InvalidActionTokenException : IdentityException("Invalid or expired verification token")

class RateLimitExceededException(val retryAfterSeconds: Long) : IdentityException("Rate limit exceeded")

class IdentityUnavailableException(message: String) : IdentityException(message)
