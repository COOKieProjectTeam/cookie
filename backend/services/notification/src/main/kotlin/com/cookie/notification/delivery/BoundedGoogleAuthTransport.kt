package com.cookie.notification.delivery

import com.cookie.platform.web.BoundedHttpExchange
import com.google.api.client.http.HttpTransport
import com.google.api.client.http.LowLevelHttpRequest
import com.google.api.client.http.LowLevelHttpResponse
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.net.http.HttpRequest
import java.time.Duration

/** The official credential library performs signing; its network exchange uses our bounded transport. */
internal class BoundedGoogleAuthTransport(private val http: BoundedHttpExchange) : HttpTransport() {
    override fun buildRequest(method: String, url: String): LowLevelHttpRequest {
        require(method == "POST" && url == "https://oauth2.googleapis.com/token")
        return object : LowLevelHttpRequest() {
            private val headers = mutableListOf<Pair<String, String>>()
            override fun addHeader(name: String, value: String) { headers += name to value }
            override fun execute(): LowLevelHttpResponse {
                try {
                    val body = ByteArrayOutputStream().also { streamingContent?.writeTo(it) }.toByteArray()
                    require(body.size <= 65_536)
                    val builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
                    headers.filterNot { it.first.equals("Content-Length", true) }.forEach { builder.header(it.first, it.second) }
                    contentType?.let { builder.header("Content-Type", it) }
                    contentEncoding?.let { builder.header("Content-Encoding", it) }
                    val response = http.send(builder.POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(), Duration.ofSeconds(10))
                    val responseHeaders = response.headers().map().flatMap { (name, values) -> values.map { name to it } }
                    return object : LowLevelHttpResponse() {
                        override fun getContent() = response.body().inputStream()
                        override fun getContentEncoding(): String? = response.headers().firstValue("Content-Encoding").orElse(null)
                        override fun getContentLength(): Long = response.body().size.toLong()
                        override fun getContentType(): String? = response.headers().firstValue("Content-Type").orElse(null)
                        override fun getStatusLine(): String = "HTTP ${response.statusCode()}"
                        override fun getStatusCode(): Int = response.statusCode()
                        override fun getReasonPhrase(): String = ""
                        override fun getHeaderCount(): Int = responseHeaders.size
                        override fun getHeaderName(index: Int): String = responseHeaders[index].first
                        override fun getHeaderValue(index: Int): String = responseHeaders[index].second
                    }
                } catch (_: Exception) { throw IOException("OAuth token exchange unavailable") }
            }
        }
    }
}
