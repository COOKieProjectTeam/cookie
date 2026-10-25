package com.cookie.mobile

import com.cookie.mobile.generated.api.ProfileApi
import com.sun.net.httpserver.HttpServer
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals

class GeneratedUserClientTest {
    @Test fun userRequestsSendTheConfiguredBearerToken() = runBlocking {
        val authorization = CompletableFuture<String?>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/me/profile") { exchange ->
            authorization.complete(exchange.requestHeaders.getFirst("Authorization"))
            exchange.responseHeaders.set("Content-Type", "application/json")
            val response = "{}".toByteArray()
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        val engine = CIO.create()
        try {
            val client = ProfileApi(baseUrl = "http://127.0.0.1:${server.address.port}/v1", httpClientEngine = engine)
            client.setBearerToken("synthetic-test-token")
            assertEquals(200, client.getProfile().status)
            assertEquals("Bearer synthetic-test-token", authorization.get(5, TimeUnit.SECONDS))
        } finally {
            engine.close()
            server.stop(0)
        }
    }
}
