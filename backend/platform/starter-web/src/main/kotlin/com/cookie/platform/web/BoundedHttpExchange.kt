package com.cookie.platform.web

import java.io.ByteArrayOutputStream
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit

/** Bounds both body size and the complete exchange, including a stalled response body. */
class BoundedHttpExchange(private val client: HttpClient) {
    fun send(request: HttpRequest, timeout: Duration, maximumBytes: Int = 65_536): HttpResponse<ByteArray> {
        val pending = client.sendAsync(request, HttpResponse.BodyHandler { LimitedBody(maximumBytes) })
        return try {
            pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS)
        } catch (exception: Exception) {
            pending.cancel(true)
            if (exception is InterruptedException) Thread.currentThread().interrupt()
            throw exception
        }
    }

    private class LimitedBody(private val maximumBytes: Int) : HttpResponse.BodySubscriber<ByteArray> {
        private val result = CompletableFuture<ByteArray>()
        private val bytes = ByteArrayOutputStream()
        private var subscription: Flow.Subscription? = null
        override fun getBody(): CompletionStage<ByteArray> = result
        override fun onSubscribe(value: Flow.Subscription) {
            subscription = value
            value.request(1)
        }
        override fun onNext(buffers: List<ByteBuffer>) {
            for (buffer in buffers) {
                if (buffer.remaining() > maximumBytes - bytes.size()) {
                    subscription?.cancel()
                    result.completeExceptionally(IllegalStateException("HTTP response exceeds configured limit"))
                    return
                }
                val part = ByteArray(buffer.remaining())
                buffer.get(part)
                bytes.write(part)
            }
            subscription?.request(1)
        }
        override fun onError(throwable: Throwable) { result.completeExceptionally(throwable) }
        override fun onComplete() { result.complete(bytes.toByteArray()) }
    }
}
