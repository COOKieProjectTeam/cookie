package com.cookie.progress.client

import com.cookie.progress.client.generated.api.ProgressInternalApi
import com.cookie.progress.client.generated.infrastructure.Serializer
import com.cookie.progress.client.generated.model.ProgressDay
import org.springframework.http.HttpHeaders
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.web.client.RestClient
import java.net.URI
import java.net.http.HttpClient
import java.time.Duration

/** The caller supplies independently issued workload and delegated user tokens for each invocation. */
class ProgressTodayClient(origin: URI, allowLocalHttp: Boolean = false) {
    init {
        val loopback = origin.host in setOf("127.0.0.1", "::1", "[::1]", "localhost")
        require(origin.scheme == "https" || (allowLocalHttp && loopback && origin.scheme == "http"))
        require(origin.host != null && origin.path in setOf("", "/") && origin.userInfo == null && origin.query == null && origin.fragment == null)
    }

    @Suppress("DEPRECATION") // Generated 7.24 models use the Jackson 2 Kotlin module.
    private val http = RestClient.builder().baseUrl(origin.toString())
        .requestFactory(JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(TIMEOUT).followRedirects(HttpClient.Redirect.NEVER).build(),
        ).apply { setReadTimeout(TIMEOUT) })
        .messageConverters { converters -> converters.add(0, MappingJackson2HttpMessageConverter(Serializer.jacksonObjectMapper)) }
        .build()

    fun today(workloadToken: String, delegatedToken: String, traceparent: String? = null): ProgressDay {
        val client = http.mutate().defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer $workloadToken").build()
        return ProgressInternalApi(client).getProgressTodayInternal(delegatedToken, traceparent)
    }

    companion object { private val TIMEOUT = Duration.ofSeconds(2) }
}
