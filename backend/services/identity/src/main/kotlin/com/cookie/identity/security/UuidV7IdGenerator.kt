package com.cookie.identity.security

import com.cookie.identity.application.ports.IdGenerator
import org.springframework.stereotype.Component
import java.security.SecureRandom
import java.time.Clock
import java.util.UUID

@Component
class UuidV7IdGenerator(
    private val clock: Clock,
    private val random: SecureRandom,
) : IdGenerator {
    override fun next(): UUID {
        val unixMillis = clock.millis()
        require(unixMillis in 0..MAX_UUID_V7_MILLIS) { "Clock is outside the UUIDv7 timestamp range" }
        val randomA = random.nextInt(1 shl RANDOM_A_BITS).toLong()
        val randomB = random.nextLong() and RANDOM_B_MASK
        val mostSignificantBits = (unixMillis shl TIMESTAMP_SHIFT) or VERSION_BITS or randomA
        val leastSignificantBits = VARIANT_BITS or randomB
        return UUID(mostSignificantBits, leastSignificantBits)
    }

    companion object {
        private const val RANDOM_A_BITS = 12
        private const val TIMESTAMP_SHIFT = 16
        private const val VERSION_BITS = 0x7000L
        private const val VARIANT_BITS = Long.MIN_VALUE
        private const val RANDOM_B_MASK = 0x3FFF_FFFF_FFFF_FFFFL
        private const val MAX_UUID_V7_MILLIS = 0x0000_FFFF_FFFF_FFFFL
    }
}
