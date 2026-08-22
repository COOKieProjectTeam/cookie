package com.cookie.identity.security

import com.cookie.identity.application.IdentityUnavailableException
import com.cookie.identity.application.ports.PasswordHashing
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import org.springframework.stereotype.Component
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

@Component
class Argon2PasswordHasher : PasswordHashing {
    private val encoder = Argon2PasswordEncoder(SALT_LENGTH, HASH_LENGTH, PARALLELISM, MEMORY_KIB, ITERATIONS)
    private val permits = Semaphore(maxOf(1, minOf(MAX_CONCURRENT_HASHES, Runtime.getRuntime().availableProcessors() / 2)))

    override val dummyHash: String = requireNotNull(encoder.encode(DUMMY_PASSWORD))

    override fun encode(password: String): String = withPermit { requireNotNull(encoder.encode(password)) }

    override fun matches(password: String, encoded: String): Boolean = withPermit { encoder.matches(password, encoded) }

    private fun <T> withPermit(block: () -> T): T {
        val acquired = try {
            permits.tryAcquire(PERMIT_WAIT_SECONDS, TimeUnit.SECONDS)
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IdentityUnavailableException("Password hashing was interrupted")
        }
        if (!acquired) throw IdentityUnavailableException("Password hashing capacity is exhausted")
        return try {
            block()
        } finally {
            permits.release()
        }
    }

    companion object {
        private const val SALT_LENGTH = 16
        private const val HASH_LENGTH = 32
        private const val PARALLELISM = 1
        private const val MEMORY_KIB = 19_456
        private const val ITERATIONS = 2
        private const val MAX_CONCURRENT_HASHES = 4
        private const val PERMIT_WAIT_SECONDS = 2L
        private const val DUMMY_PASSWORD = "DummyPassword-OnlyForEqualWork-1"
    }
}
