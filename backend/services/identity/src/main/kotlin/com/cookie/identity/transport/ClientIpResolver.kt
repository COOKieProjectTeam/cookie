package com.cookie.identity.transport

import com.cookie.identity.config.IdentityProperties
import jakarta.servlet.http.HttpServletRequest
import org.springframework.stereotype.Component
import java.net.InetAddress

/**
 * Resolves an end-user address without trusting client-controlled forwarding
 * headers. A forwarding chain is considered only when the direct peer belongs
 * to an explicitly configured proxy network.
 */
@Component
class ClientIpResolver(properties: IdentityProperties) {
    private val trustedProxies = properties.trustedProxyCidrs.map(IpNetwork::parse)

    fun resolve(request: HttpServletRequest): String {
        val peer = parseAddress(request.remoteAddr) ?: return UNKNOWN
        if (!isTrusted(peer)) return peer.hostAddress

        val forwarded = forwardedChain(request) ?: return peer.hostAddress
        for (candidate in forwarded.asReversed()) {
            if (!isTrusted(candidate)) return candidate.hostAddress
        }
        return forwarded.firstOrNull()?.hostAddress ?: peer.hostAddress
    }

    private fun forwardedChain(request: HttpServletRequest): List<InetAddress>? {
        val values = request.getHeaders(FORWARDED_FOR_HEADER)?.toList().orEmpty()
        if (values.isEmpty()) return emptyList()
        val combined = values.joinToString(",")
        if (combined.length > MAX_FORWARDED_HEADER_LENGTH) return null
        val tokens = combined.split(',')
        if (tokens.size > MAX_FORWARDED_HOPS) return null
        return tokens.map { token -> parseAddress(token.trim()) ?: return null }
    }

    private fun isTrusted(address: InetAddress): Boolean = trustedProxies.any { it.contains(address) }

    private fun parseAddress(value: String?): InetAddress? {
        val literal = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching { InetAddress.ofLiteral(literal) }.getOrNull()
    }

    private class IpNetwork private constructor(
        private val network: ByteArray,
        private val prefixLength: Int,
    ) {
        fun contains(candidate: InetAddress): Boolean {
            val bytes = candidate.address
            if (bytes.size != network.size) return false
            val fullBytes = prefixLength / Byte.SIZE_BITS
            val remainingBits = prefixLength % Byte.SIZE_BITS
            for (index in 0 until fullBytes) {
                if (bytes[index] != network[index]) return false
            }
            if (remainingBits == 0) return true
            val mask = (0xFF shl (Byte.SIZE_BITS - remainingBits)) and 0xFF
            return (bytes[fullBytes].toInt() and mask) == (network[fullBytes].toInt() and mask)
        }

        companion object {
            fun parse(raw: String): IpNetwork {
                val parts = raw.trim().split('/', limit = 2)
                require(parts.size == 2) { "Trusted proxy network must use CIDR notation: $raw" }
                val address = runCatching { InetAddress.ofLiteral(parts[0]) }
                    .getOrElse { throw IllegalArgumentException("Trusted proxy CIDR has an invalid address: $raw", it) }
                val prefix = parts[1].toIntOrNull()
                    ?: throw IllegalArgumentException("Trusted proxy CIDR has an invalid prefix: $raw")
                require(prefix in 0..address.address.size * Byte.SIZE_BITS) {
                    "Trusted proxy CIDR prefix is out of range: $raw"
                }
                return IpNetwork(address.address.masked(prefix), prefix)
            }

            private fun ByteArray.masked(prefixLength: Int): ByteArray = copyOf().also { bytes ->
                val fullBytes = prefixLength / Byte.SIZE_BITS
                val remainingBits = prefixLength % Byte.SIZE_BITS
                if (remainingBits != 0 && fullBytes < bytes.size) {
                    val mask = (0xFF shl (Byte.SIZE_BITS - remainingBits)) and 0xFF
                    bytes[fullBytes] = (bytes[fullBytes].toInt() and mask).toByte()
                }
                for (index in (fullBytes + if (remainingBits == 0) 0 else 1) until bytes.size) {
                    bytes[index] = 0
                }
            }
        }
    }

    private companion object {
        const val FORWARDED_FOR_HEADER = "X-Forwarded-For"
        const val MAX_FORWARDED_HOPS = 32
        const val MAX_FORWARDED_HEADER_LENGTH = 2_048
        const val UNKNOWN = "unknown"
    }
}
