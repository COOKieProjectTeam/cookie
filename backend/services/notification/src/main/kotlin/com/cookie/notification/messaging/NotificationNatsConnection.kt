package com.cookie.notification.messaging

import io.nats.client.*
import io.nats.client.api.*
import jakarta.annotation.PreDestroy
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.time.Duration

@Component
@ConfigurationProperties("cookie.notification")
class NotificationMessagingProperties {
    var natsUrl="nats://localhost:4222"
    var natsCredentialsPath=""
    var natsTruststorePath=""
    var natsTruststorePassword=""
    var consumerEnabled=true
}

@Component
class NotificationNatsConnection(private val properties:NotificationMessagingProperties,environment:Environment) {
    val development=environment.acceptsProfiles(Profiles.of("dev","test"))
    @Volatile private var connection:Connection?=null
    init {
        if(!development) {
            require(properties.natsUrl.startsWith("tls://")) { "Production NATS requires TLS" }
            require(properties.natsCredentialsPath.isNotBlank() && Files.isRegularFile(Path.of(properties.natsCredentialsPath)) && Files.isReadable(Path.of(properties.natsCredentialsPath))) { "NATS workload credentials are required" }
            require(properties.natsTruststorePath.isNotBlank() && Files.isRegularFile(Path.of(properties.natsTruststorePath)) && Files.isReadable(Path.of(properties.natsTruststorePath))) { "NATS truststore is required" }
            require(properties.natsTruststorePassword.isNotBlank()) { "NATS truststore password is required" }
            val store=KeyStore.getInstance("JKS")
            Files.newInputStream(Path.of(properties.natsTruststorePath)).use { store.load(it,properties.natsTruststorePassword.toCharArray()) }
            require(store.size()>0) { "NATS truststore is empty" }
        }
    }
    @Synchronized fun connection():Connection {
        connection?.let {
            if(it.status==Connection.Status.CONNECTED) return it
            if(it.status!=Connection.Status.CLOSED) throw IllegalStateException("NATS recovering")
        }
        return Nats.connect(options()).also { connected ->
            try {
            if(development) {
                val manager=connected.jetStreamManagement()
                if(runCatching { manager.getStreamInfo(STREAM) }.isFailure) {
                    runCatching { manager.addStream(StreamConfiguration.builder().name(STREAM).subjects("cookie.events.>")
                        .storageType(StorageType.File).retentionPolicy(RetentionPolicy.Limits).maxAge(Duration.ofDays(7))
                        .maxBytes(1_073_741_824).maxMessages(1_000_000).maximumMessageSize(1_048_576).replicas(1).build()) }
                        .getOrElse { manager.getStreamInfo(STREAM) }
                }
                manager.addOrUpdateConsumer(STREAM,ConsumerConfiguration.builder().durable(NotificationEventHandler.CONSUMER)
                    .ackPolicy(AckPolicy.Explicit).deliverPolicy(DeliverPolicy.All).ackWait(Duration.ofSeconds(60)).maxAckPending(100)
                    .maxDeliver(-1).filterSubjects(NotificationEventHandler.PRODUCERS.keys.map { "cookie.events.$it.v1" }).build())
            }
            connection=connected
            } catch(exception:Exception) {
                connected.runCatching { close() }
                throw exception
            }
        }
    }
    fun options(operatorCredentials:String?=null):Options = Options.Builder().server(properties.natsUrl)
        .connectionName(if(operatorCredentials==null) "cookie-notification" else "cookie-notification-replay")
        .connectionTimeout(Duration.ofSeconds(2)).reconnectWait(Duration.ofSeconds(1)).maxReconnects(-1)
        .inboxPrefix(if(operatorCredentials==null) "_INBOX.cookie.notification" else "_INBOX.cookie.notification_operator")
        .apply {
            val path=operatorCredentials?:properties.natsCredentialsPath
            if(path.isNotBlank()) credentialPath(path)
            if(!development) {
                truststorePath(properties.natsTruststorePath)
                truststorePassword(properties.natsTruststorePassword.toCharArray())
                // Resolve inside SocketDataPort so TLS verifies the broker URI's DNS name, not an IP.
                hostnameResolveMode(Options.HostnameResolveMode.HappyEyeballs)
                dataPortType(HostnameVerifyingSocketDataPort::class.java.name)
            }
        }.build()
    @PreDestroy @Synchronized fun close() { connection?.runCatching { close() }; connection=null }
    companion object { const val STREAM="COOKIE_EVENTS" }
}
