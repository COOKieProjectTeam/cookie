package com.cookie.user.messaging

import com.cookie.platform.messaging.HostnameVerifyingSocketDataPort
import com.cookie.user.config.UserProperties
import com.cookie.user.persistence.LifecycleEvents
import io.nats.client.*
import io.nats.client.api.*
import jakarta.annotation.PreDestroy
import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.time.Duration

@Component
class UserBroker(private val properties:UserProperties,environment:Environment) {
    private val local=environment.acceptsProfiles(Profiles.of("dev","test"))
    private var current:Connection?=null
    init {
        if(!local) {
            require(properties.messagingEnabled)
            require(properties.natsUrl.startsWith("tls://"))
            require(properties.natsCredentialsPath.isNotBlank() && Files.isReadable(Path.of(properties.natsCredentialsPath)))
            require(properties.natsTruststorePath.isNotBlank() && properties.natsTruststorePassword.isNotBlank())
            val store=KeyStore.getInstance("JKS")
            Files.newInputStream(Path.of(properties.natsTruststorePath)).use { store.load(it,properties.natsTruststorePassword.toCharArray()) }
            require(store.size()>0)
        }
    }
    @Synchronized fun connection():Connection {
        current?.let {
            if(it.status==Connection.Status.CONNECTED) return it
            if(it.status!=Connection.Status.CLOSED) throw IllegalStateException("Broker reconnecting")
        }
        val options=Options.Builder().server(properties.natsUrl).connectionName("cookie-user")
            .connectionTimeout(Duration.ofSeconds(2)).reconnectWait(Duration.ofSeconds(1)).maxReconnects(-1)
            .inboxPrefix("_INBOX.cookie.user").apply {
                if(properties.natsCredentialsPath.isNotBlank()) credentialPath(properties.natsCredentialsPath)
                if(!local) {
                    truststorePath(properties.natsTruststorePath);truststorePassword(properties.natsTruststorePassword.toCharArray())
                    hostnameResolveMode(Options.HostnameResolveMode.HappyEyeballs)
                    dataPortType(HostnameVerifyingSocketDataPort::class.java.name)
                }
            }.build()
        val connected=Nats.connect(options)
        try {
            if(local) {
                val manager=connected.jetStreamManagement()
                try { manager.getStreamInfo(STREAM) } catch (_:Exception) {
                    runCatching { manager.addStream(StreamConfiguration.builder().name(STREAM).subjects("cookie.events.>")
                        .storageType(StorageType.File).retentionPolicy(RetentionPolicy.Limits).maxAge(Duration.ofDays(7))
                        .maxBytes(1_073_741_824).maxMessages(1_000_000).maximumMessageSize(1_048_576).replicas(1).build()) }
                        .getOrElse { manager.getStreamInfo(STREAM) }
                }
                manager.addOrUpdateConsumer(STREAM,ConsumerConfiguration.builder().durable(LifecycleEvents.CONSUMER)
                    .ackPolicy(AckPolicy.Explicit).deliverPolicy(DeliverPolicy.All).ackWait(Duration.ofSeconds(60))
                    .maxAckPending(100).maxDeliver(-1).filterSubjects(LifecycleEvents.TYPES.map { "cookie.events.$it.v1" }).build())
            }
            current=connected
            return connected
        } catch(e:Exception) { runCatching { connected.close() };throw e }
    }
    @PreDestroy @Synchronized fun close() { current?.runCatching { close() };current=null }
    companion object { const val STREAM="COOKIE_EVENTS" }
}
