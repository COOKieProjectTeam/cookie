package com.cookie.identity.domain

import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

class PasswordHasher {
    private val encoder = Argon2PasswordEncoder(16, 32, 1, 19_456, 2)
    private val permits = Semaphore(maxOf(1, minOf(4, Runtime.getRuntime().availableProcessors() / 2)))
    val dummyHash: String = requireNotNull(encoder.encode("DummyPassword-OnlyForEqualWork-1"))

    fun encode(password: String): String = withPermit { requireNotNull(encoder.encode(password)) }

    fun matches(password: String, encoded: String): Boolean = withPermit { encoder.matches(password, encoded) }

    private fun <T> withPermit(block: () -> T): T {
        if (!permits.tryAcquire(2, TimeUnit.SECONDS)) {
            throw IdentityUnavailableException("Password hashing capacity is exhausted")
        }
        return try {
            block()
        } finally {
            permits.release()
        }
    }
}
