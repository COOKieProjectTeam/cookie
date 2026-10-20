package com.cookie.platform.web

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.nio.charset.StandardCharsets

class BoundedRequestBodyFilterTest {
    private val filter = BoundedRequestBodyFilter(maximumBytes = 8)

    @Test
    fun `passes and preserves a body within the limit`() {
        val request = request("12345678")
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        filter.doFilter(request, response, chain)

        assertThat(chain.request?.inputStream?.readAllBytes()?.decodeToString()).isEqualTo("12345678")
    }

    @Test
    fun `rejects a declared oversized body`() {
        val response = MockHttpServletResponse()

        filter.doFilter(request("123456789"), response, MockFilterChain())

        assertThat(response.status).isEqualTo(413)
        assertThat(response.contentAsString).contains("PAYLOAD_TOO_LARGE")
    }

    @Test
    fun `rejects an oversized body when content length is unknown`() {
        val request = object : MockHttpServletRequest() {
            override fun getContentLengthLong(): Long = -1
        }.apply {
            method = "POST"
            setContent("123456789".toByteArray(StandardCharsets.UTF_8))
        }
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, MockFilterChain())

        assertThat(response.status).isEqualTo(413)
    }

    private fun request(body: String) = MockHttpServletRequest().apply {
        method = "POST"
        setContent(body.toByteArray(StandardCharsets.UTF_8))
    }
}
