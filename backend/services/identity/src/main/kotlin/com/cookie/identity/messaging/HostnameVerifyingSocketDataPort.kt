package com.cookie.identity.messaging

import io.nats.client.Options
import io.nats.client.impl.SocketDataPort
import java.io.IOException
import javax.net.ssl.SSLSocket

/** Adds peer-name verification missing from jnats' socket data port. */
class HostnameVerifyingSocketDataPort : SocketDataPort() {
    private lateinit var configuredOptions: Options

    override fun afterConstruct(options: Options) {
        configuredOptions = options
    }

    @Throws(IOException::class)
    override fun upgradeToSecure() {
        val options = configuredOptions
        val sslContext = options.sslContext ?: throw IOException("NATS TLS context is not configured")
        val tlsSocket = sslContext.socketFactory.createSocket(socket, host, port, true) as SSLSocket
        try {
            tlsSocket.useClientMode = true
            tlsSocket.sslParameters = tlsSocket.sslParameters.apply {
                endpointIdentificationAlgorithm = "HTTPS"
            }
            tlsSocket.soTimeout = options.connectionTimeout.toMillis()
                .coerceIn(1, Int.MAX_VALUE.toLong())
                .toInt()
            tlsSocket.startHandshake()
            tlsSocket.soTimeout = options.socketReadTimeoutMillis
        } catch (exception: Exception) {
            runCatching { tlsSocket.close() }
            if (exception is IOException) throw exception
            throw IOException("NATS TLS handshake failed", exception)
        }
        socket = tlsSocket
        this.`in` = tlsSocket.inputStream
        out = tlsSocket.outputStream
        isSecure = true
    }
}
