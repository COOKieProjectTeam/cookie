package com.cookie.identity.messaging

import com.cookie.identity.config.IdentityProperties
import io.nats.client.Connection
import io.nats.client.JetStream
import io.nats.client.Nats
import io.nats.client.Options
import io.nats.client.api.RetentionPolicy
import io.nats.client.api.StorageType
import io.nats.client.api.StreamConfiguration
import jakarta.annotation.PreDestroy
import org.springframework.stereotype.Component
import java.time.Duration

@Component
class NatsJetStreamConnection(private val properties: IdentityProperties) {
    private val connectionMonitor = Any()

    @Volatile
    private var connection: Connection? = null

    fun jetStream(): JetStream {
        val active = connection?.takeIf { it.status == Connection.Status.CONNECTED }
        if (active != null) return active.jetStream()

        return synchronized(connectionMonitor) {
            val rechecked = connection?.takeIf { it.status == Connection.Status.CONNECTED }
            if (rechecked != null) return@synchronized rechecked.jetStream()

            connection?.runCatching { close() }
            val options = Options.Builder()
                .server(properties.natsUrl)
                .connectionName("cookie-identity")
                .connectionTimeout(Duration.ofSeconds(2))
                .reconnectWait(Duration.ofSeconds(1))
                .maxReconnects(-1)
                .build()
            val connected = Nats.connect(options)
            ensureStream(connected)
            connection = connected
            connected.jetStream()
        }
    }

    fun isReady(): Boolean = connection?.status == Connection.Status.CONNECTED

    @PreDestroy
    fun close() {
        synchronized(connectionMonitor) {
            connection?.runCatching { close() }
            connection = null
        }
    }

    private fun ensureStream(connected: Connection) {
        val management = connected.jetStreamManagement()
        val exists = runCatching { management.getStreamInfo(STREAM_NAME) }.isSuccess
        if (!exists) {
            runCatching {
                management.addStream(
                    StreamConfiguration.builder()
                        .name(STREAM_NAME)
                        .description("COOKie domain events")
                        .subjects("cookie.events.>")
                        .retentionPolicy(RetentionPolicy.Limits)
                        .storageType(StorageType.File)
                        .duplicateWindow(Duration.ofMinutes(10))
                        .build(),
                )
            }.getOrElse {
                management.getStreamInfo(STREAM_NAME)
            }
        }
    }

    companion object {
        const val STREAM_NAME = "COOKIE_EVENTS"
    }
}
