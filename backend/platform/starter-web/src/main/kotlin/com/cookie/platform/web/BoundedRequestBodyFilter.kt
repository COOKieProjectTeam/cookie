package com.cookie.platform.web

import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.filter.OncePerRequestFilter
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/** Buffers a deliberately small API body so chunked requests cannot bypass the size limit. */
class BoundedRequestBodyFilter(
    private val maximumBytes: Int,
) : OncePerRequestFilter() {
    init {
        require(maximumBytes > 0) { "Maximum request body size must be positive" }
    }

    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        request.method !in METHODS_WITH_BODY

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        if (request.contentLengthLong > maximumBytes) {
            reject(request, response)
            return
        }
        val body = request.inputStream.readNBytes(maximumBytes + 1)
        if (body.size > maximumBytes) {
            reject(request, response)
            return
        }
        filterChain.doFilter(BufferedBodyRequest(request, body), response)
    }

    private fun reject(request: HttpServletRequest, response: HttpServletResponse) {
        response.status = HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE
        response.characterEncoding = StandardCharsets.UTF_8.name()
        response.contentType = "application/json"
        val requestId = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE) as? String
        val suffix = requestId?.let { ",\"requestId\":\"$it\"" }.orEmpty()
        response.writer.write(
            "{\"code\":\"PAYLOAD_TOO_LARGE\",\"message\":\"Request body is too large\"$suffix}",
        )
    }

    private class BufferedBodyRequest(
        request: HttpServletRequest,
        private val body: ByteArray,
    ) : HttpServletRequestWrapper(request) {
        override fun getInputStream(): ServletInputStream = ByteArrayServletInputStream(body)

        override fun getReader(): BufferedReader = BufferedReader(
            InputStreamReader(inputStream, characterEncoding?.let(Charset::forName) ?: StandardCharsets.UTF_8),
        )

        override fun getContentLength(): Int = body.size

        override fun getContentLengthLong(): Long = body.size.toLong()
    }

    private class ByteArrayServletInputStream(body: ByteArray) : ServletInputStream() {
        private val input = ByteArrayInputStream(body)

        override fun read(): Int = input.read()

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = input.read(buffer, offset, length)

        override fun isFinished(): Boolean = input.available() == 0

        override fun isReady(): Boolean = true

        override fun setReadListener(listener: ReadListener) {
            try {
                if (!isFinished) listener.onDataAvailable()
                if (isFinished) listener.onAllDataRead()
            } catch (exception: IOException) {
                listener.onError(exception)
            }
        }
    }

    private companion object {
        val METHODS_WITH_BODY = setOf("POST", "PUT", "PATCH")
    }
}
