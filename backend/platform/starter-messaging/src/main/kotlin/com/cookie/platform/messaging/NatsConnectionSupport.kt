package com.cookie.platform.messaging

import io.nats.client.Connection
import io.nats.client.Nats
import io.nats.client.Options
import io.nats.client.api.AckPolicy
import io.nats.client.api.ConsumerConfiguration
import io.nats.client.api.DeliverPolicy
import io.nats.client.api.RetentionPolicy
import io.nats.client.api.StorageType
import io.nats.client.api.StreamConfiguration
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.time.Duration

/** Shared transport policy; subjects, durable names and credentials remain service-owned. */
data class NatsConnectionSettings(
    val url: String,
    val development: Boolean,
    val credentials: String,
    val truststore: String,
    val truststorePassword: String,
) {
    fun validate() {
        if (development) return
        require(url.startsWith("tls://")) { "Production NATS requires TLS" }
        requireReadableFile(credentials, "NATS credentials")
        requireReadableFile(truststore, "NATS truststore")
        require(truststorePassword.isNotBlank()) { "NATS truststore password is required" }
        val store = KeyStore.getInstance("JKS")
        Files.newInputStream(Path.of(truststore)).use { store.load(it, truststorePassword.toCharArray()) }
        require(store.size() > 0) { "NATS truststore is empty" }
    }

    fun options(connectionName: String, inboxPrefix: String): Options {
        validate()
        return Options.Builder().server(url).connectionName(connectionName)
            .connectionTimeout(Duration.ofSeconds(2)).reconnectWait(Duration.ofSeconds(1)).maxReconnects(-1)
            .inboxPrefix(inboxPrefix).apply {
                if (credentials.isNotBlank()) credentialPath(credentials)
                if (!development) {
                    truststorePath(truststore)
                    truststorePassword(truststorePassword.toCharArray())
                    hostnameResolveMode(Options.HostnameResolveMode.HappyEyeballs)
                    dataPortType(HostnameVerifyingSocketDataPort::class.java.name)
                }
            }.build()
    }

    private fun requireReadableFile(raw: String, label: String) {
        require(raw.isNotBlank() && Files.isRegularFile(Path.of(raw)) && Files.isReadable(Path.of(raw))) {
            "$label must be a readable regular file"
        }
    }
}

/** One connection per owner; a closed connection can be replaced after failed recovery. */
class NatsConnectionLifecycle(
    private val options: () -> Options,
    private val initialize: (Connection) -> Unit = {},
    private val connect: (Options) -> Connection = { Nats.connect(it) },
) : AutoCloseable {
    private var current: Connection? = null

    @Synchronized
    fun connection(): Connection {
        current?.let {
            if (it.status == Connection.Status.CONNECTED) return it
            check(it.status == Connection.Status.CLOSED) { "NATS recovering" }
        }
        val connected = connect(options())
        try {
            initialize(connected)
            current = connected
            return connected
        } catch (failure: Exception) {
            runCatching { connected.close() }
            throw failure
        }
    }

    @Synchronized
    override fun close() {
        current?.runCatching { close() }
        current = null
    }
}

/** Called only by explicit dev/test adapters, never using production workload credentials. */
object DevelopmentJetStreamTopology {
    fun ensure(connection: Connection, stream: String, durable: String, subjects: List<String>) {
        val manager = connection.jetStreamManagement()
        if (runCatching { manager.getStreamInfo(stream) }.isFailure) {
            runCatching {
                manager.addStream(StreamConfiguration.builder().name(stream).subjects("cookie.events.>")
                    .storageType(StorageType.File).retentionPolicy(RetentionPolicy.Limits).maxAge(Duration.ofDays(7))
                    .maxBytes(1_073_741_824).maxMessages(1_000_000).maximumMessageSize(1_048_576).replicas(1).build())
            }.getOrElse { manager.getStreamInfo(stream) }
        }
        manager.addOrUpdateConsumer(stream, ConsumerConfiguration.builder().durable(durable)
            .ackPolicy(AckPolicy.Explicit).deliverPolicy(DeliverPolicy.All).ackWait(Duration.ofSeconds(60))
            .maxAckPending(100).maxDeliver(-1).filterSubjects(subjects).build())
    }
}
