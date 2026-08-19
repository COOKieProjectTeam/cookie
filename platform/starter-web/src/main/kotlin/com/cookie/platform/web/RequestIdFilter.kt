package com.cookie.platform.web

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

class RequestIdFilter : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val requestId = request.getHeader(REQUEST_ID_HEADER)
            ?.takeIf { value -> runCatching { UUID.fromString(value) }.isSuccess }
            ?: UUID.randomUUID().toString()
        response.setHeader(REQUEST_ID_HEADER, requestId)
        request.setAttribute(REQUEST_ID_ATTRIBUTE, requestId)
        MDC.put(REQUEST_ID_MDC, requestId)
        try {
            filterChain.doFilter(request, response)
        } finally {
            MDC.remove(REQUEST_ID_MDC)
        }
    }

    companion object {
        const val REQUEST_ID_HEADER = "X-Request-Id"
        const val REQUEST_ID_MDC = "requestId"
        const val REQUEST_ID_ATTRIBUTE = "com.cookie.requestId"
    }
}
