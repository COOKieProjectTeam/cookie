package com.cookie.identity.domain

import java.security.SecureRandom
import java.time.Clock
import java.util.UUID

class UuidV7Generator(
    private val clock: Clock,
    private val random: SecureRandom,
) {
    fun next(): UUID {
        val unixMillis = clock.millis() and 0x0000_FFFF_FFFF_FFFFL
        val randomA = random.nextInt(1 shl 12).toLong()
        val randomB = random.nextLong() and 0x3FFF_FFFF_FFFF_FFFFL
        val mostSignificantBits = (unixMillis shl 16) or 0x7000L or randomA
        val leastSignificantBits = Long.MIN_VALUE or randomB
        return UUID(mostSignificantBits, leastSignificantBits)
    }
}
