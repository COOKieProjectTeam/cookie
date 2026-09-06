package com.cookie.identity.transport

import com.cookie.identity.config.IdentityProperties
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest

class ClientIpResolverTest {
    @Test
    fun `ignores a spoofed forwarding header from an untrusted peer`() {
        val request = request("198.51.100.8", "203.0.113.9")

        assertThat(ClientIpResolver(IdentityProperties()).resolve(request)).isEqualTo("198.51.100.8")
    }

    @Test
    fun `walks a trusted proxy chain from right to left`() {
        val request = request("10.0.0.3", "203.0.113.9, 10.0.0.2")
        val resolver = ClientIpResolver(IdentityProperties(trustedProxyCidrs = listOf("10.0.0.0/8")))

        assertThat(resolver.resolve(request)).isEqualTo("203.0.113.9")
    }

    @Test
    fun `falls back to the peer when a trusted proxy supplies a malformed chain`() {
        val request = request("10.0.0.3", "attacker.example, 10.0.0.2")
        val resolver = ClientIpResolver(IdentityProperties(trustedProxyCidrs = listOf("10.0.0.0/8")))

        assertThat(resolver.resolve(request)).isEqualTo("10.0.0.3")
    }

    @Test
    fun `supports ipv6 proxy networks`() {
        val request = request("2001:db8:abcd::2", "2001:db8:ffff::42")
        val resolver = ClientIpResolver(IdentityProperties(trustedProxyCidrs = listOf("2001:db8:abcd::/48")))

        assertThat(resolver.resolve(request)).isEqualTo("2001:db8:ffff:0:0:0:0:42")
    }

    @Test
    fun `rejects malformed trusted proxy configuration`() {
        assertThatThrownBy {
            ClientIpResolver(IdentityProperties(trustedProxyCidrs = listOf("10.0.0.0/99")))
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun request(peer: String, forwardedFor: String) = MockHttpServletRequest().apply {
        remoteAddr = peer
        addHeader("X-Forwarded-For", forwardedFor)
    }
}
